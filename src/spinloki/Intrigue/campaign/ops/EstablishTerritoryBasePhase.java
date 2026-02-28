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
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase that sends a supply fleet escorted by strong military fleets to a
 * territory system to establish a base there.
 *
 * <p>Uses {@link RouteManager}: the fleet is only physically spawned when the
 * player is nearby. If the supply fleet survives the establishment period
 * (orbiting the target location), the phase succeeds. If destroyed, it fails.</p>
 *
 * <p>If the fleet is never spawned (route expires abstractly, or sim mode),
 * the phase auto-succeeds. Disruption of base establishment will be handled
 * by future opposing ops.</p>
 */
public class EstablishTerritoryBasePhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(EstablishTerritoryBasePhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_establish_terr_";

    private final String factionId;
    private final String sourceMarketId;
    private final String territoryId;
    private final int escortFP;
    private final int supplyFP;
    private final float establishDays;
    private final String subfactionName;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean routeStarted = false;

    private transient CampaignFleetAPI fleet;
    private String routeSource;

    /**
     * @param factionId       parent faction ID
     * @param sourceMarketId  market to spawn the fleet from
     * @param territoryId     target territory (for system selection)
     * @param escortFP        fleet points for the military escort
     * @param supplyFP        fleet points for the supply/freighter ships
     * @param establishDays   how many days the fleet orbits the target
     * @param subfactionName  display name for fleet labeling
     */
    public EstablishTerritoryBasePhase(String factionId, String sourceMarketId,
                                       String territoryId,
                                       int escortFP, int supplyFP,
                                       float establishDays, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.territoryId = territoryId;
        this.escortFP = escortFP;
        this.supplyFP = supplyFP;
        this.establishDays = establishDays;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    @Override
    public void advance(float days) {
        if (done) return;

        // ── Sim mode: auto-succeed ──
        if (!PhaseUtil.isSectorAvailable()) {
            if (!routeStarted) {
                log.info("EstablishTerritoryBasePhase: no sector (sim mode); auto-completing as success.");
                succeeded = true;
                done = true;
            }
            return;
        }

        // ── Register route once ──
        if (!routeStarted) {
            startRoute();
            return;
        }

        // ── Check if route expired (abstract completion) ──
        RouteData route = findOurRoute();
        if (route == null && !done) {
            log.info("EstablishTerritoryBasePhase: route expired (abstract establishment complete). Success.");
            succeeded = true;
            done = true;
        }
    }

    private void startRoute() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("EstablishTerritoryBasePhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        // Pick a system in the territory to orbit in
        SectorEntityToken targetEntity = pickTargetInTerritory();
        SectorEntityToken sourceEntity = source.getPrimaryEntity();

        if (targetEntity == null) {
            // Fallback: orbit the source entity
            log.warning("EstablishTerritoryBasePhase: no system found in territory; using source.");
            targetEntity = sourceEntity;
        }

        routeSource = ROUTE_SOURCE_PREFIX + factionId + "_" + territoryId + "_"
                + Global.getSector().getClock().getTimestamp();

        OptionalFleetData extra = new OptionalFleetData(source, factionId);
        extra.fp = (float) (escortFP + supplyFP);
        extra.fleetType = FleetTypes.SUPPLY_FLEET;

        RouteData route = RouteManager.getInstance().addRoute(
                routeSource, source, (long) (Math.random() * Long.MAX_VALUE),
                extra, this);

        // Segment 1: travel to target system
        route.addSegment(new RouteSegment(1, sourceEntity, targetEntity));
        // Segment 2: orbit/establish at target for the establishment period
        route.addSegment(new RouteSegment(2, establishDays, targetEntity));
        // Segment 3: return home
        route.addSegment(new RouteSegment(3, targetEntity, sourceEntity));

        routeStarted = true;
        log.info("EstablishTerritoryBasePhase: registered route '" + routeSource
                + "' (" + escortFP + " escort + " + supplyFP + " supply FP, "
                + establishDays + " days).");
    }

    /**
     * Pick a star system center within the territory to orbit around.
     * Prefers systems without existing markets.
     */
    private SectorEntityToken pickTargetInTerritory() {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        IntrigueTerritory territory = territories.getById(territoryId);
        if (territory == null) return null;

        Set<String> constellationNames = new HashSet<>(territory.getConstellationNames());
        List<StarSystemAPI> candidates = new ArrayList<>();

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.hasTag(Tags.THEME_CORE) || sys.hasTag(Tags.THEME_CORE_POPULATED)) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            if (sys.getConstellation() == null) continue;
            if (constellationNames.contains(sys.getConstellation().getName())) {
                candidates.add(sys);
            }
        }

        if (candidates.isEmpty()) return null;

        // Prefer systems without existing (non-condition) markets
        List<StarSystemAPI> empty = new ArrayList<>();
        for (StarSystemAPI sys : candidates) {
            boolean hasMarket = false;
            for (SectorEntityToken entity : sys.getAllEntities()) {
                if (entity.getMarket() != null && !entity.getMarket().isPlanetConditionMarketOnly()) {
                    hasMarket = true;
                    break;
                }
            }
            if (!hasMarket) empty.add(sys);
        }

        Random rng = new Random();
        StarSystemAPI picked = empty.isEmpty()
                ? candidates.get(rng.nextInt(candidates.size()))
                : empty.get(rng.nextInt(empty.size()));

        return picked.getCenter();
    }

    private RouteData findOurRoute() {
        if (routeSource == null) return null;
        for (RouteData rd : RouteManager.getInstance().getRoutesForSource(routeSource)) {
            return rd;
        }
        return null;
    }

    // ── RouteFleetSpawner ────────────────────────────────────────────────

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI source = route.getMarket();
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("EstablishTerritoryBasePhase.spawnFleet: source market missing.");
            return null;
        }

        // Create a combined fleet: military escort + supply/freighter ships
        FleetParamsV3 params = new FleetParamsV3(
                source,
                FleetTypes.SUPPLY_FLEET,
                escortFP,       // combat escort
                supplyFP,       // freighter points
                0f, 0f, 0f, 0f, 0f
        );
        params.factionId = factionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("EstablishTerritoryBasePhase.spawnFleet: failed to create fleet.");
            return null;
        }

        fleet = created;
        fleet.setName(subfactionName + " Establishment Fleet");

        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueEstablishment", true);

        // Place at the route's current interpolated position
        RouteSegment current = route.getCurrent();
        LocationAPI loc = current != null ? current.getCurrentContainingLocation() : null;
        SectorEntityToken sourceEntity = source.getPrimaryEntity();

        if (loc != null) {
            loc.addEntity(fleet);
            SectorEntityToken dest = current.getDestination();
            if (dest != null) {
                fleet.setLocation(dest.getLocation().x, dest.getLocation().y);
            } else {
                fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
            }
        } else {
            sourceEntity.getContainingLocation().addEntity(fleet);
            fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        }

        fleet.addEventListener(this);

        // Determine assignments based on current segment
        SectorEntityToken targetEntity = pickTargetInTerritory();
        if (targetEntity == null) targetEntity = sourceEntity;

        int segIndex = route.getCurrentIndex();
        if (segIndex <= 0) {
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, targetEntity, 120f,
                    "Travelling to establishment site");
            fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, targetEntity, establishDays,
                    "Establishing base for " + subfactionName);
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                    "Returning home");
        } else if (segIndex == 1) {
            float remainingDays = establishDays;
            if (current != null) {
                remainingDays = Math.max(1f, current.daysMax - current.elapsed);
            }
            fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, targetEntity, remainingDays,
                    "Establishing base for " + subfactionName);
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                    "Returning home");
        } else {
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                    "Returning home");
        }

        log.info("EstablishTerritoryBasePhase.spawnFleet: spawned establishment fleet ("
                + escortFP + " escort + " + supplyFP + " supply FP) at segment " + segIndex);

        return fleet;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) {
        return false;
    }

    @Override
    public boolean shouldRepeat(RouteData route) {
        return false;
    }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        fleet = null;
        log.info("EstablishTerritoryBasePhase: fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;

        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            succeeded = false;
            done = true;
            removeRoute();
            log.info("EstablishTerritoryBasePhase: establishment fleet defeated in battle.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;

        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) {
            // RouteManager despawning - route continues abstractly
            return;
        }

        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            succeeded = false;
        } else {
            succeeded = true;
        }
        done = true;
        removeRoute();
        log.info("EstablishTerritoryBasePhase: fleet despawned. Reason: " + reason
                + ", succeeded: " + succeeded);
    }

    private void removeRoute() {
        RouteData route = findOurRoute();
        if (route != null) {
            RouteManager.getInstance().removeRoute(route);
        }
    }

    // ── OpPhase ──────────────────────────────────────────────────────────


    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!routeStarted) return "Preparing establishment fleet";
        if (done) return succeeded ? "Establishment fleet arrived safely" : "Establishment fleet destroyed";
        return "Establishment fleet en route";
    }

    public boolean didSucceed() {
        return succeeded;
    }
}

