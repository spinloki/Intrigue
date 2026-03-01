package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
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
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persistent ambient patrol system for territory bases.
 *
 * <p>For every subfaction that is ESTABLISHED in a territory and has a base
 * market there, this script maintains patrol fleet routes:</p>
 * <ul>
 *   <li>One large patrol fleet in the base system</li>
 *   <li>Up to 3 smaller satellite patrols in nearby territory systems</li>
 * </ul>
 *
 * <p>Routes automatically repeat: when a patrol completes its circuit, a new
 * route is created. When a fleet is destroyed in combat, the subfaction takes
 * a territory cohesion and legitimacy hit, and the route is replaced after a
 * cooldown.</p>
 *
 * <p>This is entirely separate from the ops system. These fleets exist as
 * long as the subfaction has an ESTABLISHED base in the territory.</p>
 */
public class TerritoryPatrolScript implements EveryFrameScript, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(TerritoryPatrolScript.class.getName());

    private static final String ROUTE_SOURCE_PREFIX = "intrigue_terrpatrol_";

    // ── Tuning ──────────────────────────────────────────────────────────

    /** How often we check for new/removed bases and refresh routes. */
    private static final float CHECK_INTERVAL_MIN = 6.5f;
    private static final float CHECK_INTERVAL_MAX = 7.5f;

    /** Patrol duration before route expires and is replaced. */
    private static final float PATROL_DAYS = 30f;

    /** Main patrol FP = base + territory cohesion × scale. */
    private static final int BASE_MAIN_FP = 25;
    private static final float MAIN_FP_COHESION_SCALE = 0.5f;

    /** Satellite patrol FP = base + territory cohesion × scale. */
    private static final int BASE_SAT_FP = 8;
    private static final float SAT_FP_COHESION_SCALE = 0.15f;

    /** Max satellite patrol systems per territory per subfaction. */
    private static final int MAX_SATELLITE_PATROLS = 3;

    /** Cohesion penalty when a patrol fleet is destroyed. */
    private static final int DESTROY_COHESION_PENALTY = 5;
    /** Legitimacy penalty when a patrol fleet is destroyed. */
    private static final int DESTROY_LEGITIMACY_PENALTY = 3;
    /** Extra cohesion penalty when the main (base system) patrol is destroyed. */
    private static final int DESTROY_MAIN_EXTRA_COHESION_PENALTY = 3;

    // ── State ───────────────────────────────────────────────────────────

    private final IntervalUtil interval = new IntervalUtil(CHECK_INTERVAL_MIN, CHECK_INTERVAL_MAX);

    /**
     * Active patrol slots. Key = "territoryId|subfactionId".
     * Each entry tracks the route sources for the main + satellite patrols.
     */
    private final Map<String, PatrolSlot> activeSlots = new LinkedHashMap<>();

    // ── EveryFrameScript ────────────────────────────────────────────────

    @Override
    public boolean isDone() { return false; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        float days = Misc.getDays(amount);
        interval.advance(days);
        if (!interval.intervalElapsed()) return;

        refreshPatrols();
    }

    // ── Core logic ──────────────────────────────────────────────────────

    private void refreshPatrols() {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return;

        // Collect all currently-valid (territory, subfaction) pairs
        Set<String> validKeys = new HashSet<>();

        for (IntrigueTerritory territory : territories.getAll()) {
            for (String sfId : territory.getActiveSubfactionIds()) {
                if (!territory.getPresence(sfId).isEstablishedOrHigher()) continue;
                String baseMarketId = territory.getBaseMarketId(sfId);
                if (baseMarketId == null || baseMarketId.isEmpty()) continue;

                String key = slotKey(territory.getTerritoryId(), sfId);
                validKeys.add(key);

                PatrolSlot slot = activeSlots.get(key);
                if (slot == null) {
                    // New establishment — create patrols
                    slot = createPatrolSlot(territory, sfId, baseMarketId);
                    if (slot != null) {
                        activeSlots.put(key, slot);
                        log.info("TerritoryPatrolScript: created patrols for " + sfId
                                + " in " + territory.getName());
                    }
                } else {
                    // Existing — check if routes expired and need replacement
                    slot.refreshExpiredRoutes(territory, sfId, baseMarketId);
                }
            }
        }

        // Remove slots for subfactions that are no longer established
        Iterator<Map.Entry<String, PatrolSlot>> it = activeSlots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PatrolSlot> entry = it.next();
            if (!validKeys.contains(entry.getKey())) {
                entry.getValue().removeAllRoutes();
                it.remove();
                log.info("TerritoryPatrolScript: removed patrols for " + entry.getKey());
            }
        }
    }

    private PatrolSlot createPatrolSlot(IntrigueTerritory territory, String sfId, String baseMarketId) {
        IntrigueSubfaction sf = IntrigueServices.subfactions().getById(sfId);
        if (sf == null) return null;

        MarketAPI baseMarket = Global.getSector().getEconomy().getMarket(baseMarketId);
        if (baseMarket == null || baseMarket.getPrimaryEntity() == null) return null;

        int terrCohesion = territory.getCohesion(sfId);
        IntrigueTerritory.Presence presence = territory.getPresence(sfId);
        float presenceMult = presence.patrolMultiplier();
        int maxSatellites = presence.maxSatellitePatrols();

        PatrolSlot slot = new PatrolSlot(territory.getTerritoryId(), sfId,
                sf.getFactionId(), sf.getName(), baseMarketId);

        // Main patrol in the base system — scaled by presence tier
        int mainFP = Math.round((BASE_MAIN_FP + terrCohesion * MAIN_FP_COHESION_SCALE) * presenceMult);
        slot.createMainRoute(baseMarket, mainFP);

        // Satellite patrols in nearby systems — count and FP scale with tier
        List<StarSystemAPI> nearbySystems = findNearbyTerritorySystems(territory, baseMarket, maxSatellites);
        int satFP = Math.round((BASE_SAT_FP + terrCohesion * SAT_FP_COHESION_SCALE) * presenceMult);
        for (StarSystemAPI sys : nearbySystems) {
            slot.createSatelliteRoute(baseMarket, sys, satFP);
        }

        return slot;
    }

    /**
     * Find systems in the territory's constellations that are not the base system.
     * Returns up to {@code maxSatellites} systems.
     */
    private static List<StarSystemAPI> findNearbyTerritorySystems(
            IntrigueTerritory territory, MarketAPI baseMarket, int maxSatellites) {
        String baseSystemId = null;
        LocationAPI baseLoc = baseMarket.getPrimaryEntity().getContainingLocation();
        if (baseLoc instanceof StarSystemAPI) {
            baseSystemId = ((StarSystemAPI) baseLoc).getId();
        }

        List<StarSystemAPI> result = new ArrayList<>();
        for (String constellationName : territory.getConstellationNames()) {
            for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                if (sys.getConstellation() != null
                        && constellationName.equals(sys.getConstellation().getName())
                        && !sys.getId().equals(baseSystemId)) {
                    result.add(sys);
                }
            }
        }
        if (result.size() > maxSatellites) {
            Collections.shuffle(result);
            result = new ArrayList<>(result.subList(0, maxSatellites));
        }
        return result;
    }

    private static String slotKey(String territoryId, String subfactionId) {
        return territoryId + "|" + subfactionId;
    }

    // ── PatrolSlot ──────────────────────────────────────────────────────

    /**
     * Tracks the active patrol routes for one subfaction in one territory.
     */
    public static class PatrolSlot implements Serializable {
        private static final long serialVersionUID = 1L;

        final String territoryId;
        final String subfactionId;
        final String factionId;
        final String subfactionName;
        final String baseMarketId;

        String mainRouteSource;
        final List<SatelliteRouteEntry> satelliteRoutes = new ArrayList<>();

        PatrolSlot(String territoryId, String subfactionId,
                   String factionId, String subfactionName, String baseMarketId) {
            this.territoryId = territoryId;
            this.subfactionId = subfactionId;
            this.factionId = factionId;
            this.subfactionName = subfactionName;
            this.baseMarketId = baseMarketId;
        }

        void createMainRoute(MarketAPI baseMarket, int mainFP) {
            if (mainRouteSource != null) return; // already active

            String source = ROUTE_SOURCE_PREFIX + "main_" + subfactionId
                    + "_" + territoryId + "_" + Global.getSector().getClock().getTimestamp();
            mainRouteSource = source;

            AmbientPatrolSpawner spawner = new AmbientPatrolSpawner(
                    factionId, baseMarketId, mainFP, PATROL_DAYS,
                    subfactionName, null, // null targetSystemId = patrol base system
                    true, territoryId, subfactionId);

            OptionalFleetData extra = new OptionalFleetData(baseMarket, factionId);
            extra.fp = (float) mainFP;
            extra.fleetType = FleetTypes.PATROL_LARGE;

            SectorEntityToken baseEntity = baseMarket.getPrimaryEntity();
            RouteData route = RouteManager.getInstance().addRoute(
                    source, baseMarket, (long) (Math.random() * Long.MAX_VALUE),
                    extra, spawner);
            route.addSegment(new RouteSegment(1, PATROL_DAYS, baseEntity));

            log.info("PatrolSlot: main patrol route created for " + subfactionName
                    + " at " + baseMarket.getName() + " (" + mainFP + " FP)");
        }

        void createSatelliteRoute(MarketAPI baseMarket, StarSystemAPI targetSys, int satFP) {
            String source = ROUTE_SOURCE_PREFIX + "sat_" + subfactionId
                    + "_" + targetSys.getId() + "_" + Global.getSector().getClock().getTimestamp();

            AmbientPatrolSpawner spawner = new AmbientPatrolSpawner(
                    factionId, baseMarketId, satFP, PATROL_DAYS,
                    subfactionName, targetSys.getId(),
                    false, territoryId, subfactionId);

            OptionalFleetData extra = new OptionalFleetData(baseMarket, factionId);
            extra.fp = (float) satFP;
            extra.fleetType = FleetTypes.PATROL_SMALL;

            RouteData route = RouteManager.getInstance().addRoute(
                    source, baseMarket, (long) (Math.random() * Long.MAX_VALUE),
                    extra, spawner);
            route.addSegment(new RouteSegment(1, PATROL_DAYS, targetSys.getCenter()));

            satelliteRoutes.add(new SatelliteRouteEntry(source, targetSys.getId()));

            log.info("PatrolSlot: satellite patrol route created for " + subfactionName
                    + " in " + targetSys.getName() + " (" + satFP + " FP)");
        }

        /** Check if routes expired and recreate them. */
        void refreshExpiredRoutes(IntrigueTerritory territory, String sfId, String baseMarketId) {
            MarketAPI baseMarket = Global.getSector().getEconomy().getMarket(baseMarketId);
            if (baseMarket == null || baseMarket.getPrimaryEntity() == null) return;

            int terrCohesion = territory.getCohesion(sfId);

            // Check main route
            if (mainRouteSource != null && !hasActiveRoute(mainRouteSource)) {
                mainRouteSource = null;
                int mainFP = BASE_MAIN_FP + Math.round(terrCohesion * MAIN_FP_COHESION_SCALE);
                createMainRoute(baseMarket, mainFP);
            }

            // Check satellite routes
            int satFP = BASE_SAT_FP + Math.round(terrCohesion * SAT_FP_COHESION_SCALE);
            Iterator<SatelliteRouteEntry> it = satelliteRoutes.iterator();
            List<SatelliteRouteEntry> toRecreate = new ArrayList<>();
            while (it.hasNext()) {
                SatelliteRouteEntry entry = it.next();
                if (!hasActiveRoute(entry.routeSource)) {
                    it.remove();
                    toRecreate.add(entry);
                }
            }
            for (SatelliteRouteEntry entry : toRecreate) {
                StarSystemAPI sys = Global.getSector().getStarSystem(entry.targetSystemId);
                if (sys != null) {
                    createSatelliteRoute(baseMarket, sys, satFP);
                }
            }
        }

        void removeAllRoutes() {
            if (mainRouteSource != null) {
                removeRoutesForSource(mainRouteSource);
                mainRouteSource = null;
            }
            for (SatelliteRouteEntry entry : satelliteRoutes) {
                removeRoutesForSource(entry.routeSource);
            }
            satelliteRoutes.clear();
        }

        private static boolean hasActiveRoute(String source) {
            for (RouteData rd : RouteManager.getInstance().getRoutesForSource(source)) {
                return true; // at least one exists
            }
            return false;
        }

        private static void removeRoutesForSource(String source) {
            for (RouteData rd : RouteManager.getInstance().getRoutesForSource(source)) {
                RouteManager.getInstance().removeRoute(rd);
            }
        }
    }

    public static class SatelliteRouteEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        final String routeSource;
        final String targetSystemId;

        SatelliteRouteEntry(String routeSource, String targetSystemId) {
            this.routeSource = routeSource;
            this.targetSystemId = targetSystemId;
        }
    }

    // ── AmbientPatrolSpawner ────────────────────────────────────────────

    /**
     * Fleet spawner for ambient territory patrols. Handles both the main
     * (base system) patrol and satellite (nearby system) patrols.
     *
     * <p>When a fleet is destroyed, applies cohesion and legitimacy penalties
     * to the owning subfaction.</p>
     */
    public static class AmbientPatrolSpawner implements RouteFleetSpawner, FleetEventListener, Serializable {
        private static final long serialVersionUID = 1L;

        private final String factionId;
        private final String baseMarketId;
        private final int combatFP;
        private final float patrolDays;
        private final String subfactionName;
        private final String targetSystemId; // null = patrol base system
        private final boolean isMainPatrol;
        private final String territoryId;
        private final String subfactionId;

        private transient CampaignFleetAPI fleet;

        AmbientPatrolSpawner(String factionId, String baseMarketId, int combatFP,
                             float patrolDays, String subfactionName, String targetSystemId,
                             boolean isMainPatrol, String territoryId, String subfactionId) {
            this.factionId = factionId;
            this.baseMarketId = baseMarketId;
            this.combatFP = combatFP;
            this.patrolDays = patrolDays;
            this.subfactionName = subfactionName;
            this.targetSystemId = targetSystemId;
            this.isMainPatrol = isMainPatrol;
            this.territoryId = territoryId;
            this.subfactionId = subfactionId;
        }

        @Override
        public CampaignFleetAPI spawnFleet(RouteData route) {
            MarketAPI source = route.getMarket();
            if (source == null || source.getPrimaryEntity() == null) return null;

            String fleetType = isMainPatrol ? FleetTypes.PATROL_LARGE : FleetTypes.PATROL_SMALL;
            FleetParamsV3 params = new FleetParamsV3(
                    source, fleetType, combatFP, 0f, 0f, 0f, 0f, 0f, 0f);
            params.factionId = factionId;

            CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
            if (created == null || created.isEmpty()) return null;

            fleet = created;
            fleet.setName(subfactionName + (isMainPatrol ? " Garrison Patrol" : " Patrol"));
            fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
            fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
            fleet.getMemoryWithoutUpdate().set("$intrigueAmbientPatrol", true);
            if (!isMainPatrol) {
                fleet.getMemoryWithoutUpdate().set("$intrigueSatellitePatrol", true);
            }

            // Determine patrol location
            SectorEntityToken patrolTarget;
            if (targetSystemId != null) {
                StarSystemAPI targetSys = Global.getSector().getStarSystem(targetSystemId);
                if (targetSys != null) {
                    targetSys.addEntity(fleet);
                    patrolTarget = targetSys.getCenter();
                    fleet.setLocation(patrolTarget.getLocation().x, patrolTarget.getLocation().y);
                } else {
                    SectorEntityToken baseEntity = source.getPrimaryEntity();
                    baseEntity.getContainingLocation().addEntity(fleet);
                    fleet.setLocation(baseEntity.getLocation().x, baseEntity.getLocation().y);
                    patrolTarget = baseEntity;
                }
            } else {
                // Main patrol — stay in the base system
                SectorEntityToken baseEntity = source.getPrimaryEntity();
                baseEntity.getContainingLocation().addEntity(fleet);
                fleet.setLocation(baseEntity.getLocation().x, baseEntity.getLocation().y);
                patrolTarget = baseEntity;
            }

            fleet.addEventListener(this);

            float remainingDays = patrolDays;
            RouteSegment current = route.getCurrent();
            if (current != null) {
                remainingDays = Math.max(1f, current.daysMax - current.elapsed);
            }

            fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, patrolTarget, remainingDays,
                    "Patrolling on behalf of " + subfactionName);
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN,
                    source.getPrimaryEntity(), 120f, "Returning to base");

            log.info("AmbientPatrol: spawned " + (isMainPatrol ? "main" : "satellite")
                    + " patrol (" + combatFP + " FP) for " + subfactionName);
            return fleet;
        }

        @Override
        public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

        @Override
        public boolean shouldRepeat(RouteData route) { return false; }

        @Override
        public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
            fleet = null;
        }

        // ── FleetEventListener — apply penalties on destruction ──────────

        @Override
        public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
            if (fleet == null || this.fleet == null || fleet != this.fleet) return;
            if (battle.wasFleetDefeated(fleet, primaryWinner)) {
                applyDestructionPenalty();
            }
        }

        @Override
        public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
            if (fleet == null || this.fleet == null || fleet != this.fleet) return;
            if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;
            if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
                applyDestructionPenalty();
            }
            this.fleet = null;
        }

        private void applyDestructionPenalty() {
            IntrigueSubfaction sf = IntrigueServices.subfactions().getById(subfactionId);
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            IntrigueTerritory territory = territories != null ? territories.getById(territoryId) : null;

            int cohesionLoss = DESTROY_COHESION_PENALTY
                    + (isMainPatrol ? DESTROY_MAIN_EXTRA_COHESION_PENALTY : 0);

            if (territory != null) {
                int before = territory.getCohesion(subfactionId);
                territory.setCohesion(subfactionId, before - cohesionLoss);
                log.info("AmbientPatrol destroyed: " + subfactionName
                        + " territory cohesion -" + cohesionLoss
                        + " in " + territory.getName()
                        + " (" + before + " -> " + territory.getCohesion(subfactionId) + ")"
                        + (isMainPatrol ? " [MAIN patrol]" : " [satellite]"));
            }

            if (sf != null) {
                int before = sf.getLegitimacy();
                sf.setLegitimacy(before - DESTROY_LEGITIMACY_PENALTY);
                log.info("AmbientPatrol destroyed: " + subfactionName
                        + " legitimacy -" + DESTROY_LEGITIMACY_PENALTY
                        + " (" + before + " -> " + sf.getLegitimacy() + ")");
            }
        }
    }
}

