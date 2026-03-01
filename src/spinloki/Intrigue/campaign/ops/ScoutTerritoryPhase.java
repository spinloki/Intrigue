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
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase that scouts a territory by dispatching one small fleet per star system
 * in the territory's constellations.
 *
 * <p>Uses {@link RouteManager} so fleets are only physically spawned when the
 * player is nearby. Each fleet patrols its assigned system for the scouting
 * duration.</p>
 *
 * <h3>Success conditions:</h3>
 * <ul>
 *   <li><b>Fleets spawned (player nearby):</b> Succeed if fewer than half the
 *       scout fleets were destroyed.</li>
 *   <li><b>Abstract (player far away / routes expired):</b> Succeed if no
 *       hostile subfaction established in the territory has cohesion &gt; 50.</li>
 *   <li><b>Sim mode (no sector):</b> Uses the hostile-cohesion check.</li>
 * </ul>
 */
public class ScoutTerritoryPhase implements OpPhase, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(ScoutTerritoryPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_scout_";

    private static final int COMBINED_HOSTILE_COHESION_THRESHOLD = 100;

    private final String factionId;
    private final String sourceMarketId;
    private final String subfactionId;
    private final String territoryId;
    private final int combatFP;
    private final float scoutDays;
    private final String subfactionName;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean routesStarted = false;

    /** One handler per system in the territory. */
    private final List<ScoutFleetHandler> handlers = new ArrayList<>();

    /** Set to true if any handler's spawnFleet() was actually called by RouteManager. */
    private boolean anyFleetSpawned = false;

    /**
     * @param factionId       parent faction ID (for fleet creation)
     * @param sourceMarketId  market to spawn fleets from
     * @param subfactionId    the scouting subfaction's ID
     * @param territoryId     the territory being scouted
     * @param combatFP        fleet points per scout fleet
     * @param scoutDays       how many days each fleet patrols
     * @param subfactionName  display name for fleet labeling
     */
    public ScoutTerritoryPhase(String factionId, String sourceMarketId,
                               String subfactionId, String territoryId,
                               int combatFP, float scoutDays, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.subfactionId = subfactionId;
        this.territoryId = territoryId;
        this.combatFP = combatFP;
        this.scoutDays = scoutDays;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    @Override
    public void advance(float days) {
        if (done) return;

        // ── Sim mode: use hostile-cohesion check ──
        if (!PhaseUtil.isSectorAvailable()) {
            if (!routesStarted) {
                succeeded = passesHostileCohesionCheck();
                done = true;
                log.info("ScoutTerritoryPhase: sim mode; hostile-cohesion check → "
                        + (succeeded ? "SUCCESS" : "FAILURE"));
            }
            return;
        }

        // ── Start routes (one per system in territory) ──
        if (!routesStarted) {
            startRoutes();
            if (handlers.isEmpty()) {
                // No systems found in territory - succeed by default
                log.info("ScoutTerritoryPhase: no systems found in territory "
                        + territoryId + "; auto-success.");
                succeeded = true;
                done = true;
            }
            return;
        }

        // ── Check if all routes are done ──
        boolean allDone = true;
        for (ScoutFleetHandler handler : handlers) {
            if (!handler.isDone()) {
                allDone = false;
                break;
            }
        }

        if (allDone) {
            resolveOutcome();
        }
    }

    private void startRoutes() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("ScoutTerritoryPhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        List<StarSystemAPI> systems = findSystemsInTerritory();

        if (systems.isEmpty()) {
            log.info("ScoutTerritoryPhase: no star systems found in territory " + territoryId);
            succeeded = true;
            done = true;
            return;
        }

        long timestamp = Global.getSector().getClock().getTimestamp();

        for (int i = 0; i < systems.size(); i++) {
            StarSystemAPI sys = systems.get(i);
            String routeSource = ROUTE_SOURCE_PREFIX + subfactionId + "_" + territoryId
                    + "_" + i + "_" + timestamp;

            ScoutFleetHandler handler = new ScoutFleetHandler(
                    this, factionId, sourceMarketId, combatFP, scoutDays,
                    subfactionName, routeSource, sys);
            handler.startRoute(source);
            handlers.add(handler);
        }

        routesStarted = true;
        log.info("ScoutTerritoryPhase: dispatched " + handlers.size()
                + " scout fleets into territory " + territoryId);
    }

    private void resolveOutcome() {
        if (anyFleetSpawned) {
            // Player was nearby - resolve based on fleet survival
            int total = handlers.size();
            int destroyed = 0;
            for (ScoutFleetHandler h : handlers) {
                if (h.wasDestroyed()) destroyed++;
            }
            succeeded = destroyed < Math.ceil(total / 2.0);
            log.info("ScoutTerritoryPhase: fleet-based resolution. "
                    + destroyed + "/" + total + " destroyed → "
                    + (succeeded ? "SUCCESS" : "FAILURE"));
        } else {
            // All routes expired abstractly - use hostile-cohesion check
            succeeded = passesHostileCohesionCheck();
            log.info("ScoutTerritoryPhase: abstract resolution (hostile-cohesion check) → "
                    + (succeeded ? "SUCCESS" : "FAILURE"));
        }
        done = true;
    }

    /**
     * Check if the combined cohesion of all hostile established subfactions
     * in this territory exceeds the threshold.
     *
     * @return true if scouting should succeed (combined hostile cohesion ≤ 100)
     */
    private boolean passesHostileCohesionCheck() {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return true;

        IntrigueTerritory territory = territories.getById(territoryId);
        if (territory == null) return true;

        int combinedHostileCohesion = 0;

        for (String otherSfId : territory.getActiveSubfactionIds()) {
            if (otherSfId.equals(subfactionId)) continue;

            IntrigueTerritory.Presence presence = territory.getPresence(otherSfId);
            if (!presence.isEstablishedOrHigher()) continue;

            IntrigueSubfaction otherSf = IntrigueServices.subfactions().getById(otherSfId);
            if (otherSf == null) continue;

            if (!IntrigueServices.hostility().areHostile(factionId, otherSf.getFactionId())) continue;

            combinedHostileCohesion += territory.getCohesion(otherSfId);
        }

        if (combinedHostileCohesion > COMBINED_HOSTILE_COHESION_THRESHOLD) {
            log.info("ScoutTerritoryPhase: combined hostile cohesion "
                    + combinedHostileCohesion + " > " + COMBINED_HOSTILE_COHESION_THRESHOLD
                    + " in " + territory.getName());
            return false;
        }
        return true;
    }

    private List<StarSystemAPI> findSystemsInTerritory() {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return Collections.emptyList();

        IntrigueTerritory territory = territories.getById(territoryId);
        if (territory == null) return Collections.emptyList();

        Set<String> constellationNames = new HashSet<>(territory.getConstellationNames());
        List<StarSystemAPI> result = new ArrayList<>();

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.hasTag(Tags.THEME_CORE) || sys.hasTag(Tags.THEME_CORE_POPULATED)) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            if (sys.getConstellation() == null) continue;
            if (constellationNames.contains(sys.getConstellation().getName())) {
                result.add(sys);
            }
        }

        return result;
    }

    /** Called by handlers when RouteManager physically spawns a fleet. */
    void notifyFleetSpawned() {
        anyFleetSpawned = true;
    }

    // ── OpPhase ──────────────────────────────────────────────────────────


    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!routesStarted) return "Preparing scout fleets";
        if (done) return succeeded ? "Scouting complete" : "Scouts driven off";
        int active = 0;
        for (ScoutFleetHandler h : handlers) {
            if (!h.isDone()) active++;
        }
        return "Scouting territory (" + active + "/" + handlers.size() + " fleets active)";
    }

    public boolean didSucceed() {
        return succeeded;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Inner class: manages a single scout fleet's route lifecycle
    // ══════════════════════════════════════════════════════════════════════

    static class ScoutFleetHandler implements RouteFleetSpawner, FleetEventListener, Serializable {

        private final ScoutTerritoryPhase parent;
        private final String factionId;
        private final String sourceMarketId;
        private final int combatFP;
        private final float patrolDays;
        private final String subfactionName;
        private final String routeSource;
        private final String targetSystemId;

        private transient CampaignFleetAPI fleet;
        private boolean done = false;
        private boolean destroyed = false;

        ScoutFleetHandler(ScoutTerritoryPhase parent,
                          String factionId, String sourceMarketId,
                          int combatFP, float patrolDays, String subfactionName,
                          String routeSource, StarSystemAPI targetSystem) {
            this.parent = parent;
            this.factionId = factionId;
            this.sourceMarketId = sourceMarketId;
            this.combatFP = combatFP;
            this.patrolDays = patrolDays;
            this.subfactionName = subfactionName;
            this.routeSource = routeSource;
            this.targetSystemId = targetSystem.getId();
        }

        void startRoute(MarketAPI source) {
            OptionalFleetData extra = new OptionalFleetData(source, factionId);
            extra.fp = (float) combatFP;
            extra.fleetType = FleetTypes.PATROL_SMALL;

            SectorEntityToken sourceEntity = source.getPrimaryEntity();

            // Find an entity in the target system to patrol around
            StarSystemAPI targetSys = findTargetSystem();
            SectorEntityToken patrolTarget = (targetSys != null)
                    ? targetSys.getCenter() : sourceEntity;

            RouteData route = RouteManager.getInstance().addRoute(
                    routeSource, source, (long) (Math.random() * Long.MAX_VALUE),
                    extra, this);

            // Travel to system, patrol, return
            if (targetSys != null
                    && targetSys != sourceEntity.getContainingLocation()) {
                route.addSegment(new RouteSegment(1, sourceEntity, patrolTarget));
                route.addSegment(new RouteSegment(2, patrolDays, patrolTarget));
                route.addSegment(new RouteSegment(3, patrolTarget, sourceEntity));
            } else {
                route.addSegment(new RouteSegment(1, patrolDays, sourceEntity));
            }

            log.info("ScoutFleetHandler: registered route '" + routeSource
                    + "' for system "
                    + (targetSys != null ? targetSys.getBaseName() : "?")
                    + " (" + combatFP + " FP, " + patrolDays + " days)");
        }

        private StarSystemAPI findTargetSystem() {
            try {
                for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                    if (sys.getId().equals(targetSystemId)) return sys;
                }
            } catch (Exception e) {
                // Sector not available
            }
            return null;
        }

        private RouteData findOurRoute() {
            if (routeSource == null) return null;
            for (RouteData rd : RouteManager.getInstance().getRoutesForSource(routeSource)) {
                return rd;
            }
            return null;
        }

        /** Called by the parent's advance loop. */
        boolean isDone() {
            if (done) return true;
            // Check if route expired
            RouteData route = findOurRoute();
            if (route == null) {
                // Route expired abstractly - scout survived
                done = true;
            }
            return done;
        }

        boolean wasDestroyed() {
            return destroyed;
        }

        // ── RouteFleetSpawner ────────────────────────────────────────────

        @Override
        public CampaignFleetAPI spawnFleet(RouteData route) {
            MarketAPI source = route.getMarket();
            if (source == null || source.getPrimaryEntity() == null) {
                log.warning("ScoutFleetHandler.spawnFleet: source market missing.");
                return null;
            }

            FleetParamsV3 params = new FleetParamsV3(
                    source, FleetTypes.PATROL_SMALL,
                    combatFP, 0f, 0f, 0f, 0f, 0f, 0f);
            params.factionId = factionId;

            CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
            if (created == null || created.isEmpty()) {
                log.warning("ScoutFleetHandler.spawnFleet: failed to create fleet.");
                return null;
            }

            fleet = created;
            fleet.setName(subfactionName + " Scouts");
            fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
            fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
            fleet.getMemoryWithoutUpdate().set("$intrigueScout", true);

            // Place at route's current position
            RouteSegment current = route.getCurrent();
            LocationAPI loc = current != null
                    ? current.getCurrentContainingLocation() : null;
            SectorEntityToken sourceEntity = source.getPrimaryEntity();

            if (loc != null) {
                loc.addEntity(fleet);
                SectorEntityToken dest = current.getDestination();
                if (dest != null) {
                    fleet.setLocation(dest.getLocation().x, dest.getLocation().y);
                } else {
                    fleet.setLocation(sourceEntity.getLocation().x,
                            sourceEntity.getLocation().y);
                }
            } else {
                sourceEntity.getContainingLocation().addEntity(fleet);
                fleet.setLocation(sourceEntity.getLocation().x,
                        sourceEntity.getLocation().y);
            }

            fleet.addEventListener(this);

            StarSystemAPI targetSys = findTargetSystem();
            SectorEntityToken patrolTarget = (targetSys != null)
                    ? targetSys.getCenter() : sourceEntity;

            float remainingDays = patrolDays;
            if (current != null) {
                remainingDays = Math.max(1f, current.daysMax - current.elapsed);
            }

            fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, patrolTarget,
                    remainingDays, "Scouting for " + subfactionName);
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN,
                    sourceEntity, 120f, "Returning home");

            parent.notifyFleetSpawned();
            log.info("ScoutFleetHandler.spawnFleet: spawned scout (" + combatFP
                    + " FP) targeting "
                    + (targetSys != null ? targetSys.getBaseName() : "source"));
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
        }

        // ── FleetEventListener ───────────────────────────────────────────

        @Override
        public void reportBattleOccurred(CampaignFleetAPI fleet,
                                         CampaignFleetAPI primaryWinner,
                                         BattleAPI battle) {
            if (fleet == null || this.fleet == null || fleet != this.fleet) return;
            if (battle.wasFleetDefeated(fleet, primaryWinner)) {
                destroyed = true;
                done = true;
                removeRoute();
                log.info("ScoutFleetHandler: scout fleet defeated in battle.");
            }
        }

        @Override
        public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
                                                   FleetDespawnReason reason,
                                                   Object param) {
            if (fleet == null || this.fleet == null || fleet != this.fleet) return;
            if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;

            if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
                destroyed = true;
            }
            done = true;
            removeRoute();
            log.info("ScoutFleetHandler: fleet despawned. Reason: " + reason
                    + ", destroyed: " + destroyed);
        }

        private void removeRoute() {
            RouteData route = findOurRoute();
            if (route != null) RouteManager.getInstance().removeRoute(route);
        }
    }
}
