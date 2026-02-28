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
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Abstract base for phases where paired fleets from the same faction travel to
 * a meeting point, become hostile via a dissident faction, and fight.
 *
 * <p>Handles the complete multi-wave lifecycle: wave spawning on a timer,
 * per-pair arrival detection, dissident faction swap, engagement, cleanup,
 * transponder management, and route management.</p>
 *
 * <p>Subclasses provide configuration (fleet size, wave counts, fleet type,
 * naming) and battleground selection logic.</p>
 */
public abstract class FactionBattlePhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final Logger log = Logger.getLogger(FactionBattlePhase.class.getName());

    private static final String MEM_ENEMY_KEY = "$intrigueBattleEnemy";

    protected final String factionIdA;
    protected final String factionIdB;
    protected final String sourceMarketIdA;
    protected final String sourceMarketIdB;
    protected final String subfactionName;
    protected final int fleetFP;

    // State
    private boolean done = false;
    private boolean initialized = false;

    // Multi-wave tracking
    private final int totalTicks;
    private int ticksCompleted = 0;
    private float daysSinceLastTick = 0f;

    // All active fleet pairs
    protected final List<FleetPair> activePairs = new ArrayList<>();

    /** Tracks a single pair of opposing fleets. */
    protected static class FleetPair implements Serializable {
        public String routeSourceA;
        public String routeSourceB;
        public transient CampaignFleetAPI fleetA;
        public transient CampaignFleetAPI fleetB;
        public boolean engaged = false;
        public boolean resolved = false;
        public transient SectorEntityToken meetingPoint;
        public String meetingSystemId;
        /** Optional — used for precise entity restore after save/load. */
        public String meetingEntityId;
        /** Source market for fleet A (for return-home after fight). */
        public String homeMarketIdA;
        /** Source market for fleet B (for return-home after fight). */
        public String homeMarketIdB;
    }

    /**
     * Full constructor allowing different factions and spawn markets per side.
     */
    protected FactionBattlePhase(String factionIdA, String sourceMarketIdA,
                                 String factionIdB, String sourceMarketIdB,
                                 int fleetFP, String subfactionName,
                                 int minTicks, int maxTicks) {
        this.factionIdA = factionIdA;
        this.sourceMarketIdA = sourceMarketIdA;
        this.factionIdB = factionIdB;
        this.sourceMarketIdB = sourceMarketIdB;
        this.fleetFP = fleetFP;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";

        Random rng = new Random();
        this.totalTicks = minTicks + rng.nextInt(maxTicks - minTicks + 1);
    }

    /**
     * Convenience constructor: same faction and spawn market for both sides.
     */
    protected FactionBattlePhase(String factionId, String sourceMarketId,
                                 int fleetFP, String subfactionName,
                                 int minTicks, int maxTicks) {
        this(factionId, sourceMarketId, factionId, sourceMarketId,
                fleetFP, subfactionName, minTicks, maxTicks);
    }

    // ── Abstract / overridable configuration ────────────────────────────

    /** Route source prefix for RouteManager (e.g. "intrigue_infighting_"). */
    protected abstract String routeSourcePrefix();

    /** Fleet type string for FleetParamsV3 (e.g. FleetTypes.PATROL_MEDIUM). */
    protected abstract String fleetType();

    /** Days allowed for travel to the meeting point. */
    protected abstract float travelDays();

    /** Days allowed for the actual fight. */
    protected abstract float fightDays();

    /** Min pairs to spawn per wave. */
    protected abstract int minPairsPerTick();

    /** Max pairs to spawn per wave. */
    protected abstract int maxPairsPerTick();

    /** Days between spawning new waves. */
    protected abstract float tickIntervalDays();

    /** Display name for Fleet A (e.g. "Loyalists"). */
    protected abstract String fleetALabel();

    /** Display name for Fleet B (e.g. "Dissidents" or "Rebels"). */
    protected abstract String fleetBLabel();

    /**
     * Pick a meeting point / battleground for one fleet pair.
     * Called once per pair per wave. Return null if no valid location found.
     */
    protected abstract SectorEntityToken pickBattleground();

    /**
     * Build the fleet assignment description for traveling to the target system.
     * @param systemName the destination system's display name
     * @param otherFleetName the opposing fleet's display name
     */
    protected String travelToSystemText(String systemName, String otherFleetName) {
        return "Traveling to " + systemName + " with " + otherFleetName;
    }

    /**
     * Build the fleet assignment description for traveling to the specific POI.
     * @param targetName the POI's display name
     * @param otherFleetName the opposing fleet's display name
     */
    protected String travelToTargetText(String targetName, String otherFleetName) {
        return "Traveling to " + targetName + " with " + otherFleetName;
    }

    /**
     * Build the fleet assignment description for the fight itself.
     * @param otherFleetName the opposing fleet's display name
     */
    protected String fightText(String otherFleetName) {
        return "Settling their differences with " + otherFleetName;
    }

    /**
     * Build the fleet assignment description for orbiting the target while
     * waiting for the opposing fleet to arrive.
     * @param targetName the POI's display name
     * @param otherFleetName the opposing fleet's display name
     */
    protected String waitText(String targetName, String otherFleetName) {
        return fightText(otherFleetName);
    }

    /** Status text when phase hasn't started yet. */
    protected String statusNotStarted() { return "Tensions rising"; }
    /** Status text when phase is complete. */
    protected String statusDone() { return "Conflict ended"; }
    /** Status text when engagements are active. */
    protected String statusEngaged(int count) { return "Fighting (" + count + " engagements)"; }
    /** Status text when fleets are en route. */
    protected String statusEnRoute() {
        return "Forces en route (wave " + ticksCompleted + "/" + totalTicks + ")";
    }

    /** Called when a pair is resolved. Subclasses can free claimed POIs etc. */
    protected void onPairResolved(FleetPair pair) {}

    /** Log tag for messages. */
    protected String logTag() { return getClass().getSimpleName(); }

    // ── Phase lifecycle ─────────────────────────────────────────────────

    @Override
    public final void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!initialized) {
                log.info(logTag() + ": no sector (sim mode); auto-completing.");
                done = true;
            }
            return;
        }

        if (!initialized) {
            spawnWave();
            initialized = true;
            return;
        }

        daysSinceLastTick += days;
        if (ticksCompleted < totalTicks && daysSinceLastTick >= tickIntervalDays()) {
            daysSinceLastTick = 0f;
            spawnWave();
        }

        for (FleetPair pair : activePairs) {
            if (pair.resolved) continue;
            advancePair(pair);
        }

        // Keep transponders on for all active fleets
        for (FleetPair pair : activePairs) {
            if (pair.fleetA != null && pair.fleetA.isAlive()) pair.fleetA.setTransponderOn(true);
            if (pair.fleetB != null && pair.fleetB.isAlive()) pair.fleetB.setTransponderOn(true);
        }

        // Check completion
        if (ticksCompleted >= totalTicks) {
            boolean allResolved = true;
            for (FleetPair pair : activePairs) {
                if (!pair.resolved) {
                    RouteData rA = findRoute(pair.routeSourceA);
                    RouteData rB = findRoute(pair.routeSourceB);
                    if (rA == null && rB == null && !pair.engaged) {
                        pair.resolved = true;
                        onPairResolved(pair);
                    } else {
                        allResolved = false;
                    }
                }
            }
            if (allResolved) {
                done = true;
                log.info(logTag() + ": all waves resolved. Done.");
            }
        }
    }

    private void advancePair(FleetPair pair) {
        if (pair.meetingPoint == null && pair.meetingSystemId != null) {
            pair.meetingPoint = restoreMeetingPoint(pair.meetingSystemId, pair.meetingEntityId);
        }

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

        if (pair.engaged && !pair.resolved) {
            boolean aGone = pair.fleetA == null || !pair.fleetA.isAlive();
            boolean bGone = pair.fleetB == null || !pair.fleetB.isAlive();
            if (aGone || bGone) {
                pair.resolved = true;
                cleanupPairSurvivor(pair);
                onPairResolved(pair);
                log.info(logTag() + ": pair resolved.");
            }
        }
    }

    // ── Wave spawning ───────────────────────────────────────────────────

    private void spawnWave() {
        MarketAPI sourceA = Global.getSector().getEconomy().getMarket(sourceMarketIdA);
        MarketAPI sourceB = Global.getSector().getEconomy().getMarket(sourceMarketIdB);
        if (sourceA == null || sourceA.getPrimaryEntity() == null) {
            log.warning(logTag() + ": source market A missing; stopping.");
            ticksCompleted = totalTicks;
            return;
        }
        if (sourceB == null || sourceB.getPrimaryEntity() == null) {
            // Fall back to source A if B is gone
            sourceB = sourceA;
        }

        Random rng = new Random();
        int min = minPairsPerTick();
        int max = maxPairsPerTick();
        int pairsThisTick = min + rng.nextInt(max - min + 1);
        SectorEntityToken sourceEntityA = sourceA.getPrimaryEntity();
        SectorEntityToken sourceEntityB = sourceB.getPrimaryEntity();
        long timestamp = Global.getSector().getClock().getTimestamp();

        int spawned = 0;
        for (int i = 0; i < pairsThisTick; i++) {
            SectorEntityToken target = pickBattleground();
            if (target == null) {
                log.warning(logTag() + ": no battleground for pair " + i + "; skipping.");
                continue;
            }

            FleetPair pair = new FleetPair();
            pair.meetingPoint = target;
            pair.meetingSystemId = target.getContainingLocation() instanceof StarSystemAPI
                    ? ((StarSystemAPI) target.getContainingLocation()).getId() : null;
            pair.meetingEntityId = target.getId();
            pair.homeMarketIdA = sourceA.getId();
            pair.homeMarketIdB = sourceB.getId();

            long seed = (long) (Math.random() * Long.MAX_VALUE);
            float totalDays = travelDays() + fightDays();
            String suffix = "_" + ticksCompleted + "_" + i + "_" + timestamp;

            // Route A — spawns from source A
            pair.routeSourceA = routeSourcePrefix() + "a" + suffix;
            OptionalFleetData extraA = new OptionalFleetData(sourceA, factionIdA);
            extraA.fp = (float) fleetFP;
            extraA.fleetType = fleetType();
            RouteData rA = RouteManager.getInstance().addRoute(
                    pair.routeSourceA, sourceA, seed, extraA, this);
            rA.addSegment(new RouteSegment(1, totalDays, sourceEntityA, target));

            // Route B — spawns from source B
            pair.routeSourceB = routeSourcePrefix() + "b" + suffix;
            OptionalFleetData extraB = new OptionalFleetData(sourceB, factionIdB);
            extraB.fp = (float) fleetFP;
            extraB.fleetType = fleetType();
            RouteData rB = RouteManager.getInstance().addRoute(
                    pair.routeSourceB, sourceB, seed + 1, extraB, this);
            rB.addSegment(new RouteSegment(1, totalDays, sourceEntityB, target));

            activePairs.add(pair);
            spawned++;
        }

        ticksCompleted++;
        log.info(logTag() + ": spawned wave " + ticksCompleted + "/" + totalTicks
                + " (" + spawned + " pairs, " + fleetFP + " FP each)");
    }

    // ── RouteFleetSpawner ───────────────────────────────────────────────

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI source = route.getMarket();
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning(logTag() + ".spawnFleet: source market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source, fleetType(),
                fleetFP, 0f, 0f, 0f, 0f, 0f, 0f);

        FleetPair pair = findPairForRoute(route.getSource());
        boolean isFleetA = pair != null && route.getSource().equals(pair.routeSourceA);

        // Use the correct faction for each side
        params.factionId = isFleetA ? factionIdA : factionIdB;

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) {
            log.warning(logTag() + ".spawnFleet: failed to create fleet.");
            return null;
        }

        String fleetName;
        String otherFleetName;
        if (isFleetA) {
            if (pair != null) pair.fleetA = fleet;
            fleetName = subfactionName + " " + fleetALabel();
            otherFleetName = subfactionName + " " + fleetBLabel();
        } else {
            if (pair != null) pair.fleetB = fleet;
            fleetName = subfactionName + " " + fleetBLabel();
            otherFleetName = subfactionName + " " + fleetALabel();
        }

        fleet.setName(fleetName);
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
        fleet.setTransponderOn(true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        fleet.addEventListener(this);

        // Resolve meeting point
        SectorEntityToken target = sourceEntity;
        if (pair != null) {
            if (pair.meetingPoint == null && pair.meetingSystemId != null) {
                pair.meetingPoint = restoreMeetingPoint(pair.meetingSystemId, pair.meetingEntityId);
            }
            if (pair.meetingPoint != null) {
                target = pair.meetingPoint;
            }
        }
        String targetName = target.getName() != null ? target.getName() : "unknown";

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
                travelDays() * 0.7f, travelToSystemText(systemName, otherFleetName));
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, target,
                travelDays() * 0.3f, travelToTargetText(targetName, otherFleetName));
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, target,
                fightDays(), waitText(targetName, otherFleetName));

        log.info(logTag() + ".spawnFleet: spawned " + fleetName
                + " (" + fleetFP + " FP) heading to " + target.getName());

        return fleet;
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
        log.info(logTag() + ": fleet despawned by RouteManager.");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        for (FleetPair pair : activePairs) {
            if (fleet == pair.fleetA) pair.fleetA = null;
            else if (fleet == pair.fleetB) pair.fleetB = null;
        }
        log.info(logTag() + ": fleet despawned. Reason: " + reason);
    }

    // ── Engagement logic ────────────────────────────────────────────────

    private void engagePair(FleetPair pair) {
        if (pair.fleetA == null || pair.fleetB == null) return;

        String originalFactionId = pair.fleetB.getFaction().getId();
        pair.fleetB.getMemoryWithoutUpdate().set("$intrigueOriginalFaction", originalFactionId);

        FactionAPI dissidentFac = DissidentFactions.get(originalFactionId);
        if (dissidentFac != null) {
            pair.fleetB.setFaction(dissidentFac.getId(), true);
        }

        pair.fleetA.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
        pair.fleetB.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
        pair.fleetA.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        pair.fleetB.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);

        pair.fleetA.getMemoryWithoutUpdate().set(MEM_ENEMY_KEY, pair.fleetB.getId());
        pair.fleetB.getMemoryWithoutUpdate().set(MEM_ENEMY_KEY, pair.fleetA.getId());

        pair.fleetA.clearAssignments();
        pair.fleetB.clearAssignments();

        pair.fleetA.addAssignment(FleetAssignment.INTERCEPT, pair.fleetB, fightDays(),
                fightText(pair.fleetB.getName()));
        pair.fleetB.addAssignment(FleetAssignment.INTERCEPT, pair.fleetA, fightDays(),
                fightText(pair.fleetA.getName()));

        pair.engaged = true;
        log.info(logTag() + ": pair engaged!");
    }

    private void cleanupPairSurvivor(FleetPair pair) {
        CampaignFleetAPI survivor = null;
        boolean isSurvivorA = false;
        if (pair.fleetA != null && pair.fleetA.isAlive()) { survivor = pair.fleetA; isSurvivorA = true; }
        if (pair.fleetB != null && pair.fleetB.isAlive()) { survivor = pair.fleetB; isSurvivorA = false; }

        if (survivor != null) {
            String originalFaction = survivor.getMemoryWithoutUpdate().getString("$intrigueOriginalFaction");
            if (originalFaction != null) {
                survivor.setFaction(originalFaction, true);
                survivor.getMemoryWithoutUpdate().unset("$intrigueOriginalFaction");
            }
            survivor.getMemoryWithoutUpdate().unset(MEM_ENEMY_KEY);
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
            survivor.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED);
            survivor.clearAssignments();

            // Send survivor to their own home market
            String homeId = isSurvivorA ? pair.homeMarketIdA : pair.homeMarketIdB;
            MarketAPI home = homeId != null ? Global.getSector().getEconomy().getMarket(homeId) : null;
            SectorEntityToken homeEntity = home != null ? home.getPrimaryEntity() : null;
            if (homeEntity != null) {
                survivor.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, homeEntity, 120f,
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

    protected static boolean hasFactionMarket(SectorEntityToken entity) {
        MarketAPI market = entity.getMarket();
        if (market == null) return false;
        String fId = market.getFactionId();
        return fId != null && !fId.isEmpty() && !"neutral".equals(fId);
    }

    private SectorEntityToken restoreMeetingPoint(String systemId, String entityId) {
        if (systemId == null) return null;
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.getId().equals(systemId)) {
                if (entityId != null) {
                    for (SectorEntityToken entity : sys.getAllEntities()) {
                        if (entityId.equals(entity.getId())) return entity;
                    }
                }
                return sys.getCenter();
            }
        }
        return null;
    }

    private FleetPair findPairForRoute(String routeSource) {
        for (FleetPair pair : activePairs) {
            if (routeSource.equals(pair.routeSourceA) || routeSource.equals(pair.routeSourceB)) {
                return pair;
            }
        }
        return null;
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
        if (!initialized) return statusNotStarted();
        if (done) return statusDone();
        int engaged = 0;
        for (FleetPair pair : activePairs) {
            if (pair.engaged && !pair.resolved) engaged++;
        }
        if (engaged > 0) return statusEngaged(engaged);
        return statusEnRoute();
    }
}








