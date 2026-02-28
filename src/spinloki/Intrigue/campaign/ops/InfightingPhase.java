package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteFleetSpawner;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase for an infighting op: two fleets from the same subfaction travel to a
 * point of interest in the territory to "settle their differences," then become
 * hostile to each other and fight.
 *
 * <p>Uses {@link RouteManager} so fleets are only physically spawned when the
 * player is nearby. When the player is far away, the route expires abstractly
 * and the phase completes (infighting happened off-screen).</p>
 *
 * <p>When spawned, both fleets travel to a random POI in the territory system.
 * Once they arrive, they are made hostile to each other via the
 * {@code $intrigueInfightingEnemy} memory key, causing them to engage.</p>
 */
public class InfightingPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final Logger log = Logger.getLogger(InfightingPhase.class.getName());

    private static final String ROUTE_SOURCE_PREFIX = "intrigue_infighting_";
    private static final String MEM_INFIGHTING_ENEMY = "$intrigueInfightingEnemy";
    private static final float TRAVEL_DAYS = 30f;
    private static final float FIGHT_DAYS = 10f;

    private final String factionId;
    private final String sourceMarketId;
    private final String territoryId;
    private final String subfactionName;
    private final int fleetFP;

    // State
    private boolean done = false;
    private boolean routesStarted = false;

    // Two route sources - one per fleet
    private String routeSourceA;
    private String routeSourceB;

    // Spawned fleet references (transient - lost on save/load)
    private transient CampaignFleetAPI fleetA;
    private transient CampaignFleetAPI fleetB;

    // Tracking engagement at POI
    private boolean fleetsEngaged = false;

    // The target entity in the territory system where fleets will meet
    private transient SectorEntityToken meetingPoint;
    private String meetingSystemId;

    public InfightingPhase(String factionId, String sourceMarketId,
                           String territoryId, int fleetFP, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.territoryId = territoryId;
        this.fleetFP = fleetFP;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!routesStarted) {
                log.info("InfightingPhase: no sector (sim mode); auto-completing.");
                done = true;
            }
            return;
        }

        if (!routesStarted) {
            startRoutes();
            return;
        }

        // Check if both routes have expired (abstract completion)
        RouteData routeA = findRoute(routeSourceA);
        RouteData routeB = findRoute(routeSourceB);
        if (routeA == null && routeB == null && !done) {
            log.info("InfightingPhase: both routes expired (abstract infighting). Done.");
            done = true;
            return;
        }

        // If fleets are spawned and haven't engaged yet, check for arrival
        if (fleetA != null && fleetB != null && !fleetsEngaged) {
            checkForArrivalAndEngage();
        }

        // If one fleet is destroyed or despawned, we're done
        if (fleetsEngaged) {
            boolean aGone = fleetA == null || !fleetA.isAlive();
            boolean bGone = fleetB == null || !fleetB.isAlive();
            if (aGone || bGone) {
                done = true;
                cleanupSurvivor();
                log.info("InfightingPhase: infighting concluded (one side defeated).");
            }
        }
    }

    private void startRoutes() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("InfightingPhase: source market missing; completing immediately.");
            done = true;
            return;
        }

        // Pick a system in the territory
        SectorEntityToken target = pickMeetingPoint();
        if (target == null) {
            log.warning("InfightingPhase: no suitable system found in territory; completing immediately.");
            done = true;
            return;
        }
        meetingPoint = target;
        meetingSystemId = target.getContainingLocation() instanceof StarSystemAPI
                ? ((StarSystemAPI) target.getContainingLocation()).getId()
                : null;

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        long seed = (long) (Math.random() * Long.MAX_VALUE);

        // Route A
        routeSourceA = ROUTE_SOURCE_PREFIX + "a_" + sourceMarketId + "_" + Global.getSector().getClock().getTimestamp();
        OptionalFleetData extraA = new OptionalFleetData(source, factionId);
        extraA.fp = (float) fleetFP;
        extraA.fleetType = FleetTypes.PATROL_MEDIUM;
        RouteData rA = RouteManager.getInstance().addRoute(
                routeSourceA, source, seed, extraA, this);
        float totalDays = TRAVEL_DAYS + FIGHT_DAYS;
        rA.addSegment(new RouteSegment(1, totalDays, sourceEntity, target));

        // Route B
        routeSourceB = ROUTE_SOURCE_PREFIX + "b_" + sourceMarketId + "_" + Global.getSector().getClock().getTimestamp();
        OptionalFleetData extraB = new OptionalFleetData(source, factionId);
        extraB.fp = (float) fleetFP;
        extraB.fleetType = FleetTypes.PATROL_MEDIUM;
        RouteData rB = RouteManager.getInstance().addRoute(
                routeSourceB, source, seed + 1, extraB, this);
        rB.addSegment(new RouteSegment(1, totalDays, sourceEntity, target));

        routesStarted = true;
        log.info("InfightingPhase: registered 2 routes at " + source.getName()
                + " heading to " + target.getName() + " (" + fleetFP + " FP each).");
    }

    /**
     * Pick a point of interest in the territory for the fleets to meet at.
     * Prefers planets, stations, asteroid fields - anything interesting.
     */
    private SectorEntityToken pickMeetingPoint() {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        IntrigueTerritory territory = territories.getById(territoryId);
        if (territory == null) return null;

        Set<String> constellationNames = new HashSet<>(territory.getConstellationNames());
        List<StarSystemAPI> systems = new ArrayList<>();

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.hasTag(Tags.THEME_CORE) || sys.hasTag(Tags.THEME_CORE_POPULATED)) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            if (sys.getConstellation() == null) continue;
            if (constellationNames.contains(sys.getConstellation().getName())) {
                systems.add(sys);
            }
        }

        if (systems.isEmpty()) return null;

        Random rng = new Random();
        StarSystemAPI system = systems.get(rng.nextInt(systems.size()));

        // Collect points of interest: planets, stations, jump points
        List<SectorEntityToken> pois = new ArrayList<>();
        for (PlanetAPI planet : system.getPlanets()) {
            if (!planet.isStar()) pois.add(planet);
        }
        for (SectorEntityToken entity : system.getEntitiesWithTag(Tags.STATION)) {
            pois.add(entity);
        }
        for (SectorEntityToken jp : system.getEntitiesWithTag(Tags.JUMP_POINT)) {
            pois.add(jp);
        }

        if (!pois.isEmpty()) {
            return pois.get(rng.nextInt(pois.size()));
        }

        // Fallback: system center
        return system.getCenter();
    }

    // ── RouteFleetSpawner ───────────────────────────────────────────────

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI source = route.getMarket();
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("InfightingPhase.spawnFleet: source market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source, FleetTypes.PATROL_MEDIUM,
                fleetFP, 0f, 0f, 0f, 0f, 0f, 0f);
        params.factionId = factionId;

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) {
            log.warning("InfightingPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        // Determine which fleet this is
        boolean isFleetA = route.getSource().equals(routeSourceA);
        if (isFleetA) {
            fleetA = fleet;
        } else {
            fleetB = fleet;
        }

        fleet.setName(subfactionName + " Dissidents");
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueInfighting", true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        fleet.addEventListener(this);

        // Restore meeting point reference if needed
        if (meetingPoint == null && meetingSystemId != null) {
            meetingPoint = restoreMeetingPoint();
        }

        SectorEntityToken target = meetingPoint != null ? meetingPoint : sourceEntity;

        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, target,
                TRAVEL_DAYS, "Settling their differences");
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, target,
                FIGHT_DAYS, "Settling their differences");

        log.info("InfightingPhase.spawnFleet: spawned " + (isFleetA ? "Fleet A" : "Fleet B")
                + " (" + fleetFP + " FP) heading to " + target.getName());

        return fleet;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        // Route manager is removing the fleet because the player moved away
        boolean isFleetA = route.getSource().equals(routeSourceA);
        if (isFleetA) {
            fleetA = null;
        } else {
            fleetB = null;
        }
        log.info("InfightingPhase: fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        // Not much to do - the fleets fighting each other is expected
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == fleetA) {
            fleetA = null;
        } else if (fleet == fleetB) {
            fleetB = null;
        }
        log.info("InfightingPhase: fleet despawned. Reason: " + reason);
    }

    // ── Engagement logic ────────────────────────────────────────────────

    /**
     * Check if both spawned fleets have arrived near the meeting point.
     * Once both are close, make them hostile to each other.
     */
    private void checkForArrivalAndEngage() {
        if (fleetA == null || fleetB == null) return;
        if (meetingPoint == null) return;

        float distA = com.fs.starfarer.api.util.Misc.getDistance(
                fleetA.getLocation(), meetingPoint.getLocation());
        float distB = com.fs.starfarer.api.util.Misc.getDistance(
                fleetB.getLocation(), meetingPoint.getLocation());

        // Consider "arrived" if within 500 units of the POI
        if (distA < 500f && distB < 500f) {
            makeHostile();
        }
    }

    /**
     * Make the two infighting fleets hostile to each other using memory flags.
     * This causes them to engage in combat without needing to change faction
     * relationships globally.
     */
    private void makeHostile() {
        if (fleetA == null || fleetB == null) return;

        // Use $hostile memory flag - each fleet targets the other
        // Store the enemy fleet's ID so they specifically target each other
        fleetA.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);
        fleetB.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);

        // Point them at each other
        fleetA.getMemoryWithoutUpdate().set(MEM_INFIGHTING_ENEMY, fleetB.getId());
        fleetB.getMemoryWithoutUpdate().set(MEM_INFIGHTING_ENEMY, fleetA.getId());

        // Clear existing assignments and tell them to intercept
        fleetA.clearAssignments();
        fleetB.clearAssignments();

        fleetA.addAssignment(FleetAssignment.INTERCEPT, fleetB, FIGHT_DAYS,
                "Settling their differences");
        fleetB.addAssignment(FleetAssignment.INTERCEPT, fleetA, FIGHT_DAYS,
                "Settling their differences");

        fleetsEngaged = true;

        log.info("InfightingPhase: fleets arrived at meeting point - engaging!");
    }

    /**
     * After one fleet is destroyed, have the survivor return home and despawn.
     */
    private void cleanupSurvivor() {
        CampaignFleetAPI survivor = null;
        if (fleetA != null && fleetA.isAlive()) survivor = fleetA;
        if (fleetB != null && fleetB.isAlive()) survivor = fleetB;

        if (survivor != null) {
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_HOSTILE);
            survivor.getMemoryWithoutUpdate().unset(MEM_INFIGHTING_ENEMY);
            survivor.clearAssignments();

            MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
            SectorEntityToken home = source != null ? source.getPrimaryEntity() : null;
            if (home != null) {
                survivor.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home, 120f,
                        "Returning home");
            } else {
                survivor.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN,
                        survivor, 1f, "Disbanding");
            }
        }

        // Clean up routes
        removeRoute(routeSourceA);
        removeRoute(routeSourceB);
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private RouteData findRoute(String source) {
        if (source == null) return null;
        for (RouteData rd : RouteManager.getInstance().getRoutesForSource(source)) {
            return rd;
        }
        return null;
    }

    private void removeRoute(String source) {
        RouteData route = findRoute(source);
        if (route != null) RouteManager.getInstance().removeRoute(route);
    }

    private SectorEntityToken restoreMeetingPoint() {
        if (meetingSystemId == null) return null;
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.getId().equals(meetingSystemId)) {
                return sys.getCenter();
            }
        }
        return null;
    }


    // ── OpPhase ─────────────────────────────────────────────────────────

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!routesStarted) return "Tensions rising";
        if (fleetsEngaged) return "Infighting in progress";
        if (done) return "Infighting subsided";
        return "Dissidents en route";
    }
}


