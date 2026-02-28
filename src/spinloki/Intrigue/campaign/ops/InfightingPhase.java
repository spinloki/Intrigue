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

    /** How many ticks (advance calls where a new wave can spawn). */
    private static final int MIN_TICKS = 3;
    private static final int MAX_TICKS = 5;
    /** How many pairs per tick. */
    private static final int MIN_PAIRS_PER_TICK = 2;
    private static final int MAX_PAIRS_PER_TICK = 4;
    /** Days between spawning new waves. */
    private static final float TICK_INTERVAL_DAYS = 5f;

    private final String factionId;
    private final String sourceMarketId;
    private final String territoryId;
    private final String subfactionName;
    private final int fleetFP;

    // State
    private boolean done = false;
    private boolean initialized = false;

    // Multi-wave tracking
    private int totalTicks;
    private int ticksCompleted = 0;
    private float daysSinceLastTick = 0f;

    // All active fleet pairs
    private final List<FleetPair> activePairs = new ArrayList<>();

    /** Tracks a single pair of opposing fleets. */
    private static class FleetPair implements Serializable {
        String routeSourceA;
        String routeSourceB;
        transient CampaignFleetAPI fleetA;
        transient CampaignFleetAPI fleetB;
        boolean engaged = false;
        boolean resolved = false;
        // Each pair has its own meeting point
        transient SectorEntityToken meetingPoint;
        String meetingSystemId;
    }

    public InfightingPhase(String factionId, String sourceMarketId,
                           String territoryId, int fleetFP, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.territoryId = territoryId;
        this.fleetFP = fleetFP;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";

        Random rng = new Random();
        this.totalTicks = MIN_TICKS + rng.nextInt(MAX_TICKS - MIN_TICKS + 1);
    }

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!initialized) {
                log.info("InfightingPhase: no sector (sim mode); auto-completing.");
                done = true;
            }
            return;
        }

        // First tick: spawn initial wave
        if (!initialized) {
            spawnWave();
            initialized = true;
            return;
        }

        // Spawn additional waves on a timer
        daysSinceLastTick += days;
        if (ticksCompleted < totalTicks && daysSinceLastTick >= TICK_INTERVAL_DAYS) {
            daysSinceLastTick = 0f;
            spawnWave();
        }

        // Update all active pairs
        for (FleetPair pair : activePairs) {
            if (pair.resolved) continue;
            advancePair(pair);
        }

        // Keep transponders on for all active fleets
        for (FleetPair pair : activePairs) {
            if (pair.fleetA != null && pair.fleetA.isAlive()) pair.fleetA.setTransponderOn(true);
            if (pair.fleetB != null && pair.fleetB.isAlive()) pair.fleetB.setTransponderOn(true);
        }

        // Check if everything is done: all ticks spent and all pairs resolved
        if (ticksCompleted >= totalTicks) {
            boolean allResolved = true;
            for (FleetPair pair : activePairs) {
                if (!pair.resolved) {
                    // Check if routes expired abstractly
                    RouteData rA = findRoute(pair.routeSourceA);
                    RouteData rB = findRoute(pair.routeSourceB);
                    if (rA == null && rB == null && !pair.engaged) {
                        pair.resolved = true;
                    } else {
                        allResolved = false;
                    }
                }
            }
            if (allResolved) {
                done = true;
                log.info("InfightingPhase: all waves resolved. Done.");
            }
        }
    }

    private void advancePair(FleetPair pair) {
        // Restore meeting point if needed (after save/load)
        if (pair.meetingPoint == null && pair.meetingSystemId != null) {
            pair.meetingPoint = restoreMeetingPoint(pair.meetingSystemId);
        }

        // Check for arrival and engage
        if (pair.fleetA != null && pair.fleetB != null && !pair.engaged) {
            if (pair.meetingPoint != null) {
                float distA = com.fs.starfarer.api.util.Misc.getDistance(
                        pair.fleetA.getLocation(), pair.meetingPoint.getLocation());
                float distB = com.fs.starfarer.api.util.Misc.getDistance(
                        pair.fleetB.getLocation(), pair.meetingPoint.getLocation());
                if (distA < 500f && distB < 500f) {
                    engagePair(pair);
                }
            }
        }

        // Check if fight is over
        if (pair.engaged && !pair.resolved) {
            boolean aGone = pair.fleetA == null || !pair.fleetA.isAlive();
            boolean bGone = pair.fleetB == null || !pair.fleetB.isAlive();
            if (aGone || bGone) {
                pair.resolved = true;
                cleanupPairSurvivor(pair);
                log.info("InfightingPhase: pair resolved (one side defeated).");
            }
        }
    }

    private SectorEntityToken restoreMeetingPoint(String systemId) {
        if (systemId == null) return null;
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.getId().equals(systemId)) {
                return sys.getCenter();
            }
        }
        return null;
    }

    private void spawnWave() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("InfightingPhase: source market missing; skipping wave.");
            ticksCompleted = totalTicks; // stop spawning
            return;
        }

        Random rng = new Random();
        int pairsThisTick = MIN_PAIRS_PER_TICK + rng.nextInt(MAX_PAIRS_PER_TICK - MIN_PAIRS_PER_TICK + 1);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        long timestamp = Global.getSector().getClock().getTimestamp();

        int spawned = 0;
        for (int i = 0; i < pairsThisTick; i++) {
            // Each pair gets its own random meeting point
            SectorEntityToken target = pickMeetingPoint();
            if (target == null) {
                log.warning("InfightingPhase: no suitable meeting point for pair " + i + "; skipping.");
                continue;
            }

            FleetPair pair = new FleetPair();
            pair.meetingPoint = target;
            pair.meetingSystemId = target.getContainingLocation() instanceof StarSystemAPI
                    ? ((StarSystemAPI) target.getContainingLocation()).getId()
                    : null;

            long seed = (long) (Math.random() * Long.MAX_VALUE);
            float totalDays = TRAVEL_DAYS + FIGHT_DAYS;

            String suffix = "_" + ticksCompleted + "_" + i + "_" + timestamp;

            // Route A
            pair.routeSourceA = ROUTE_SOURCE_PREFIX + "a" + suffix;
            OptionalFleetData extraA = new OptionalFleetData(source, factionId);
            extraA.fp = (float) fleetFP;
            extraA.fleetType = FleetTypes.PATROL_MEDIUM;
            RouteData rA = RouteManager.getInstance().addRoute(
                    pair.routeSourceA, source, seed, extraA, this);
            rA.addSegment(new RouteSegment(1, totalDays, sourceEntity, target));

            // Route B
            pair.routeSourceB = ROUTE_SOURCE_PREFIX + "b" + suffix;
            OptionalFleetData extraB = new OptionalFleetData(source, factionId);
            extraB.fp = (float) fleetFP;
            extraB.fleetType = FleetTypes.PATROL_MEDIUM;
            RouteData rB = RouteManager.getInstance().addRoute(
                    pair.routeSourceB, source, seed + 1, extraB, this);
            rB.addSegment(new RouteSegment(1, totalDays, sourceEntity, target));

            activePairs.add(pair);
            spawned++;
        }

        ticksCompleted++;
        log.info("InfightingPhase: spawned wave " + ticksCompleted + "/" + totalTicks
                + " (" + spawned + " pairs, " + fleetFP + " FP each)");
    }

    /**
     * Pick a point of interest in the territory for the fleets to meet at.
     * If territoryId is null, picks a point in the home market's system instead.
     * Prefers planets, stations, asteroid fields - anything interesting.
     */
    private SectorEntityToken pickMeetingPoint() {
        StarSystemAPI system = null;

        if (territoryId != null) {
            // Territory infighting: pick from territory constellation systems
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
            system = systems.get(new Random().nextInt(systems.size()));
        } else {
            // Home infighting: pick a nearby system (not the home system itself)
            // so the fleets actually have to travel somewhere
            MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
            if (source == null || source.getPrimaryEntity() == null) return null;
            LocationAPI homeLoc = source.getPrimaryEntity().getContainingLocation();
            if (!(homeLoc instanceof StarSystemAPI)) return null;

            StarSystemAPI homeSystem = (StarSystemAPI) homeLoc;
            float homeX = homeSystem.getLocation().x;
            float homeY = homeSystem.getLocation().y;

            // Find nearby non-hidden systems, sorted by distance
            List<StarSystemAPI> candidates = new ArrayList<>();
            for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                if (sys == homeSystem) continue;
                if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
                float dx = sys.getLocation().x - homeX;
                float dy = sys.getLocation().y - homeY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < 10000f) {
                    candidates.add(sys);
                }
            }

            if (candidates.isEmpty()) {
                // Fallback: just use the home system
                system = homeSystem;
            } else {
                system = candidates.get(new Random().nextInt(candidates.size()));
            }
        }

        if (system == null) return null;

        // Collect points of interest: planets, stations, jump points
        // Exclude entities with faction-owned markets to avoid fighting at colonies
        List<SectorEntityToken> pois = new ArrayList<>();
        for (PlanetAPI planet : system.getPlanets()) {
            if (!planet.isStar() && !hasFactionMarket(planet)) pois.add(planet);
        }
        for (SectorEntityToken entity : system.getEntitiesWithTag(Tags.STATION)) {
            if (!hasFactionMarket(entity)) pois.add(entity);
        }
        for (SectorEntityToken jp : system.getEntitiesWithTag(Tags.JUMP_POINT)) {
            pois.add(jp);
        }

        if (!pois.isEmpty()) {
            return pois.get(new Random().nextInt(pois.size()));
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

        // Find which pair this route belongs to
        FleetPair pair = findPairForRoute(route.getSource());
        boolean isFleetA = pair != null && route.getSource().equals(pair.routeSourceA);

        String fleetName;
        String otherFleetName;
        if (isFleetA) {
            if (pair != null) pair.fleetA = fleet;
            fleetName = subfactionName + " Loyalists";
            otherFleetName = subfactionName + " Dissidents";
        } else {
            if (pair != null) pair.fleetB = fleet;
            fleetName = subfactionName + " Dissidents";
            otherFleetName = subfactionName + " Loyalists";
        }

        fleet.setName(fleetName);
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueInfighting", true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);

        // Force transponder on so the player can see the fleets
        fleet.setTransponderOn(true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        fleet.addEventListener(this);

        // Resolve the pair's meeting point (restore if needed after save/load)
        SectorEntityToken target = sourceEntity; // fallback
        if (pair != null) {
            if (pair.meetingPoint == null && pair.meetingSystemId != null) {
                pair.meetingPoint = restoreMeetingPoint(pair.meetingSystemId);
            }
            if (pair.meetingPoint != null) {
                target = pair.meetingPoint;
            }
        }
        String targetName = target.getName() != null ? target.getName() : "unknown";

        // Resolve the target system name for the first leg of travel
        String systemName = "unknown";
        SectorEntityToken systemEntry = target;
        if (target.getContainingLocation() instanceof StarSystemAPI) {
            StarSystemAPI targetSystem = (StarSystemAPI) target.getContainingLocation();
            systemName = targetSystem.getBaseName();
            if (targetSystem.getCenter() != null) {
                systemEntry = targetSystem.getCenter();
            }
        }

        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, systemEntry,
                TRAVEL_DAYS * 0.7f, "Traveling to " + systemName + " with " + otherFleetName);
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, target,
                TRAVEL_DAYS * 0.3f, "Traveling to " + targetName + " with " + otherFleetName);
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, target,
                FIGHT_DAYS, "Settling their differences with " + otherFleetName);

        log.info("InfightingPhase.spawnFleet: spawned " + fleetName
                + " (" + fleetFP + " FP) heading to " + target.getName());

        return fleet;
    }

    private FleetPair findPairForRoute(String routeSource) {
        for (FleetPair pair : activePairs) {
            if (routeSource.equals(pair.routeSourceA) || routeSource.equals(pair.routeSourceB)) {
                return pair;
            }
        }
        return null;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        FleetPair pair = findPairForRoute(route.getSource());
        if (pair != null) {
            if (route.getSource().equals(pair.routeSourceA)) {
                pair.fleetA = null;
            } else {
                pair.fleetB = null;
            }
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
        for (FleetPair pair : activePairs) {
            if (fleet == pair.fleetA) pair.fleetA = null;
            else if (fleet == pair.fleetB) pair.fleetB = null;
        }
        log.info("InfightingPhase: fleet despawned. Reason: " + reason);
    }

    // ── Engagement logic ────────────────────────────────────────────────

    /**
     * Make a pair's fleets hostile and send them at each other.
     */
    private void engagePair(FleetPair pair) {
        if (pair.fleetA == null || pair.fleetB == null) return;

        String originalFactionId = pair.fleetB.getFaction().getId();
        pair.fleetB.getMemoryWithoutUpdate().set("$intrigueOriginalFaction", originalFactionId);

        FactionAPI dissidentFac = DissidentFactions.get(originalFactionId);
        if (dissidentFac != null) {
            pair.fleetB.setFaction(dissidentFac.getId(), true);
        }

        // Remove travel protection and make both fleets aggressive
        pair.fleetA.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
        pair.fleetB.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
        pair.fleetA.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        pair.fleetB.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);

        pair.fleetA.getMemoryWithoutUpdate().set(MEM_INFIGHTING_ENEMY, pair.fleetB.getId());
        pair.fleetB.getMemoryWithoutUpdate().set(MEM_INFIGHTING_ENEMY, pair.fleetA.getId());

        pair.fleetA.clearAssignments();
        pair.fleetB.clearAssignments();

        pair.fleetA.addAssignment(FleetAssignment.INTERCEPT, pair.fleetB, FIGHT_DAYS,
                "Settling their differences with " + pair.fleetB.getName());
        pair.fleetB.addAssignment(FleetAssignment.INTERCEPT, pair.fleetA, FIGHT_DAYS,
                "Settling their differences with " + pair.fleetA.getName());

        pair.engaged = true;
        log.info("InfightingPhase: pair engaged!");
    }

    /**
     * After one fleet in a pair is destroyed, clean up the survivor.
     */
    private void cleanupPairSurvivor(FleetPair pair) {
        CampaignFleetAPI survivor = null;
        if (pair.fleetA != null && pair.fleetA.isAlive()) survivor = pair.fleetA;
        if (pair.fleetB != null && pair.fleetB.isAlive()) survivor = pair.fleetB;

        if (survivor != null) {
            String originalFaction = survivor.getMemoryWithoutUpdate().getString("$intrigueOriginalFaction");
            if (originalFaction != null) {
                survivor.setFaction(originalFaction, true);
                survivor.getMemoryWithoutUpdate().unset("$intrigueOriginalFaction");
            }
            survivor.getMemoryWithoutUpdate().unset(MEM_INFIGHTING_ENEMY);
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED);
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

        removeRoute(pair.routeSourceA);
        removeRoute(pair.routeSourceB);
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /** Returns true if the entity has a market owned by a real faction (not null/neutral). */
    private static boolean hasFactionMarket(SectorEntityToken entity) {
        MarketAPI market = entity.getMarket();
        if (market == null) return false;
        String factionId = market.getFactionId();
        return factionId != null && !factionId.isEmpty() && !"neutral".equals(factionId);
    }

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



    // ── OpPhase ─────────────────────────────────────────────────────────

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!initialized) return "Tensions rising";
        if (done) return "Infighting subsided";
        int engaged = 0;
        for (FleetPair pair : activePairs) {
            if (pair.engaged && !pair.resolved) engaged++;
        }
        if (engaged > 0) return "Infighting in progress (" + engaged + " engagements)";
        return "Dissidents en route (wave " + ticksCompleted + "/" + totalTicks + ")";
    }
}


