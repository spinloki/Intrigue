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
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.campaign.HostileProximityMusicScript;
import spinloki.Intrigue.campaign.IntrigueFleetUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Phase for mischief ops targeting rallies: spawns multiple disruptor fleets
 * belonging to the mischief initiator that aggressively chase and harass
 * rally fleets in the target system.
 *
 * <p>Disruptor fleets actively seek out rally fleets (tagged with
 * {@code $intrigueRally}) and orbit them aggressively, buzzing the
 * formation. Their assignment text reads
 * "Disrupting [target]'s rally."</p>
 *
 * <p>Each spawned fleet gets a {@link HostileProximityMusicScript} for
 * proximity-based hostile audio.</p>
 */
public class RallyDisruptionPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(RallyDisruptionPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_mischief_rally_";

    /** How long the mischief fleets harass the rally. */
    private static final float DISRUPTION_DAYS = 20f;
    /** Base FP per disruptor fleet. */
    private static final int BASE_FP = 8;
    /** Number of disruptor fleets — slightly more than the rally fleet count. */
    static final int FLEET_COUNT = 7;

    private final String initiatorFactionId;
    private final String initiatorSubfactionName;
    private final String victimSubfactionName;
    private final String sourceMarketId;
    private final String rallyMarketId;
    private final int combatFPPerFleet;

    private boolean done = false;
    private boolean routesStarted = false;
    private final List<String> routeSources = new ArrayList<>();
    private transient List<CampaignFleetAPI> fleets = new ArrayList<>();
    /** Interval for re-targeting disruptors toward rally fleets. */
    private transient IntervalUtil retargetInterval;

    /**
     * @param initiatorFactionId      base faction ID of the mischief initiator
     * @param initiatorSubfactionName display name of the mischief initiator
     * @param victimSubfactionName    display name of the rally's subfaction
     * @param sourceMarketId          market ID where disruptors spawn (initiator's home)
     * @param rallyMarketId           market ID where the rally is taking place (destination)
     * @param initiatorCohesion       initiator's home cohesion (scales fleet size)
     */
    public RallyDisruptionPhase(String initiatorFactionId,
                                String initiatorSubfactionName,
                                String victimSubfactionName,
                                String sourceMarketId,
                                String rallyMarketId,
                                int initiatorCohesion) {
        this.initiatorFactionId = initiatorFactionId;
        this.initiatorSubfactionName = initiatorSubfactionName;
        this.victimSubfactionName = victimSubfactionName;
        this.sourceMarketId = sourceMarketId;
        this.rallyMarketId = rallyMarketId;
        this.combatFPPerFleet = BASE_FP + (int) (initiatorCohesion * 0.1f);
    }

    // ── Phase lifecycle ─────────────────────────────────────────────────

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!routesStarted) {
                log.info("RallyDisruptionPhase: no sector (sim mode); auto-completing.");
                done = true;
            }
            return;
        }

        if (!routesStarted) {
            startRoutes();
            return;
        }

        // Check if all routes have expired
        int activeRoutes = 0;
        for (String rs : routeSources) {
            if (findRoute(rs) != null) activeRoutes++;
        }
        if (activeRoutes == 0 && !done) {
            done = true;
            log.info("RallyDisruptionPhase: all disruption routes expired.");
            return;
        }

        // Periodically re-target disruptors toward nearby rally fleets
        if (retargetInterval == null) retargetInterval = new IntervalUtil(0.3f, 0.8f);
        retargetInterval.advance(days);
        if (retargetInterval.intervalElapsed()) {
            retargetDisruptors();
        }
    }

    /**
     * Disruptor behavior: loiter near rally fleets being provocative, but
     * flee when a rally fleet is actively chasing them (detected via the
     * {@code $intrigueRallyChasing} memory flag set by rally fleets).
     */
    private void retargetDisruptors() {
        if (fleets == null) fleets = new ArrayList<>();
        for (CampaignFleetAPI disruptor : fleets) {
            if (disruptor == null || !disruptor.isAlive()) continue;

            // First priority: is a rally fleet chasing us and actually close? Run!
            CampaignFleetAPI chaser = findChasingRallyFleet(disruptor);
            if (chaser != null) {
                float dist = Misc.getDistance(disruptor, chaser);
                if (dist < 600f) {
                    FleetAssignment currentAssign = disruptor.getCurrentAssignment() != null
                            ? disruptor.getCurrentAssignment().getAssignment() : null;
                    if (currentAssign != FleetAssignment.GO_TO_LOCATION) {
                        disruptor.clearAssignments();
                        // Pop emergency burn to get away fast
                        activateEmergencyBurn(disruptor);
                        // Flee to a random remote corner of the system
                        SectorEntityToken hideout = pickRemoteEntity(disruptor);
                        if (hideout != null) {
                            disruptor.addAssignment(FleetAssignment.GO_TO_LOCATION,
                                    hideout, 5f,
                                    "Running away from angry rally fleet");
                            disruptor.addAssignment(FleetAssignment.ORBIT_PASSIVE,
                                    hideout, 3f,
                                    "Catching their breath before going back for more");
                            // Then circle back to harass again
                            SectorEntityToken rallyPoint = getFallbackEntity();
                            if (rallyPoint != null) {
                                disruptor.addAssignment(FleetAssignment.GO_TO_LOCATION,
                                        rallyPoint, 10f,
                                        "Circling back for another pass");
                                disruptor.addAssignment(FleetAssignment.PATROL_SYSTEM,
                                        rallyPoint, DISRUPTION_DAYS,
                                        "Looking for " + victimSubfactionName + "'s rally fleets to heckle");
                            }
                        }
                    }
                    continue;
                }
            }

            // No one chasing us — find the nearest rally fleet and loiter near it
            CampaignFleetAPI nearestRally = findNearestRallyFleet(disruptor);
            if (nearestRally != null) {
                float dist = Misc.getDistance(disruptor, nearestRally);
                FleetAssignment currentAssign = disruptor.getCurrentAssignment() != null
                        ? disruptor.getCurrentAssignment().getAssignment() : null;

                if (dist < 1500f) {
                    // Close enough — orbit provocatively
                    if (currentAssign != FleetAssignment.ORBIT_AGGRESSIVE) {
                        disruptor.clearAssignments();
                        disruptor.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE,
                                nearestRally, 2f,
                                "Broadcasting rude music at " + victimSubfactionName + "'s rally");
                        SectorEntityToken fallback = getFallbackEntity();
                        if (fallback != null) {
                            disruptor.addAssignment(FleetAssignment.PATROL_SYSTEM,
                                    fallback, DISRUPTION_DAYS,
                                    "Circling back for another pass");
                        }
                    }
                } else {
                    // Move toward the rally fleet
                    if (currentAssign != FleetAssignment.GO_TO_LOCATION) {
                        disruptor.clearAssignments();
                        disruptor.addAssignment(FleetAssignment.GO_TO_LOCATION,
                                nearestRally, 5f,
                                "Scouting for rally fleets to annoy");
                        SectorEntityToken fallback = getFallbackEntity();
                        if (fallback != null) {
                            disruptor.addAssignment(FleetAssignment.PATROL_SYSTEM,
                                    fallback, DISRUPTION_DAYS,
                                    "Circling back for another pass");
                        }
                    }
                }
            }
        }
    }

    /**
     * Find the nearest rally fleet that is actively chasing disruptors.
     */
    private CampaignFleetAPI findChasingRallyFleet(CampaignFleetAPI disruptor) {
        CampaignFleetAPI nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (CampaignFleetAPI other : disruptor.getContainingLocation().getFleets()) {
            if (!other.getMemoryWithoutUpdate().getBoolean("$intrigueRallyChasing")) continue;
            if (!other.isAlive()) continue;
            float dist = Misc.getDistance(disruptor, other);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = other;
            }
        }
        return nearest;
    }

    private CampaignFleetAPI findNearestRallyFleet(CampaignFleetAPI disruptor) {
        CampaignFleetAPI nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (CampaignFleetAPI other : disruptor.getContainingLocation().getFleets()) {
            if (!other.getMemoryWithoutUpdate().getBoolean("$intrigueRally")) continue;
            if (!other.isAlive()) continue;
            float dist = Misc.getDistance(disruptor, other);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = other;
            }
        }
        return nearest;
    }

    private SectorEntityToken getFallbackEntity() {
        MarketAPI m = Global.getSector().getEconomy().getMarket(rallyMarketId);
        return m != null ? m.getPrimaryEntity() : null;
    }

    /**
     * Pick a random entity in the system that's far from the disruptor,
     * so they scatter across the whole system when fleeing. Prefers
     * planets, jump points, and stations.
     */
    private SectorEntityToken pickRemoteEntity(CampaignFleetAPI disruptor) {
        LocationAPI loc = disruptor.getContainingLocation();
        if (loc == null) return getFallbackEntity();

        List<SectorEntityToken> candidates = new ArrayList<>();
        for (SectorEntityToken entity : loc.getAllEntities()) {
            if (entity == disruptor) continue;
            if (entity instanceof CampaignFleetAPI) continue;
            // Planets, jump points, stations, markets — anything notable
            if (entity instanceof com.fs.starfarer.api.campaign.PlanetAPI
                    || entity.hasTag("jump_point")
                    || entity.hasTag("station")
                    || entity.getMarket() != null) {
                // Prefer entities that are at least somewhat far from us
                float dist = Misc.getDistance(disruptor, entity);
                if (dist > 2000f) {
                    candidates.add(entity);
                }
            }
        }

        if (candidates.isEmpty()) {
            // Fall back to anything far away at all
            for (SectorEntityToken entity : loc.getAllEntities()) {
                if (entity == disruptor) continue;
                if (entity instanceof CampaignFleetAPI) continue;
                float dist = Misc.getDistance(disruptor, entity);
                if (dist > 1000f) {
                    candidates.add(entity);
                }
            }
        }

        if (candidates.isEmpty()) return getFallbackEntity();
        return candidates.get((int) (Math.random() * candidates.size()));
    }

    /** Activate emergency burn if available and not on cooldown. */
    private void activateEmergencyBurn(CampaignFleetAPI fleet) {
        if (!fleet.hasAbility(Abilities.EMERGENCY_BURN)) return;
        com.fs.starfarer.api.characters.AbilityPlugin eb = fleet.getAbility(Abilities.EMERGENCY_BURN);
        if (eb != null && !eb.isActiveOrInProgress() && !eb.isOnCooldown()) {
            eb.activate();
        }
    }

    private void startRoutes() {
        MarketAPI sourceMarket = Global.getSector().getEconomy().getMarket(sourceMarketId);
        MarketAPI rallyMarket = Global.getSector().getEconomy().getMarket(rallyMarketId);
        if (sourceMarket == null || sourceMarket.getPrimaryEntity() == null) {
            log.warning("RallyDisruptionPhase: source market missing; aborting.");
            done = true;
            return;
        }
        if (rallyMarket == null || rallyMarket.getPrimaryEntity() == null) {
            log.warning("RallyDisruptionPhase: rally market missing; aborting.");
            done = true;
            return;
        }

        SectorEntityToken sourceEntity = sourceMarket.getPrimaryEntity();
        SectorEntityToken rallyEntity = rallyMarket.getPrimaryEntity();
        long baseSeed = Global.getSector().getClock().getTimestamp();

        for (int i = 0; i < FLEET_COUNT; i++) {
            String rs = ROUTE_SOURCE_PREFIX + sourceMarketId + "_" + baseSeed + "_" + i;

            OptionalFleetData extra = new OptionalFleetData(sourceMarket, initiatorFactionId);
            extra.fp = (float) combatFPPerFleet;
            extra.fleetType = FleetTypes.PATROL_SMALL;

            RouteData route = RouteManager.getInstance().addRoute(
                    rs, sourceMarket, (long) (Math.random() * Long.MAX_VALUE),
                    extra, this);
            // Travel from source to rally location (vanilla auto-calc), then loiter and disrupt
            route.addSegment(new RouteSegment(1, sourceEntity, rallyEntity));
            route.addSegment(new RouteSegment(1, DISRUPTION_DAYS, rallyEntity));
            routeSources.add(rs);
        }

        routesStarted = true;
        if (fleets == null) fleets = new ArrayList<>();
        log.info("RallyDisruptionPhase: registered " + FLEET_COUNT + " disruption routes from "
                + sourceMarket.getName() + " to " + rallyMarket.getName()
                + " for " + DISRUPTION_DAYS + " days (" + combatFPPerFleet + " FP each).");
    }

    private RouteData findRoute(String rs) {
        for (RouteData rd : RouteManager.getInstance().getRoutesForSource(rs)) {
            return rd;
        }
        return null;
    }

    // ── RouteFleetSpawner ───────────────────────────────────────────────

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI sourceMarket = route.getMarket();
        if (sourceMarket == null || sourceMarket.getPrimaryEntity() == null) {
            log.warning("RallyDisruptionPhase.spawnFleet: source market missing.");
            return null;
        }
        MarketAPI rallyMarket = Global.getSector().getEconomy().getMarket(rallyMarketId);
        if (rallyMarket == null || rallyMarket.getPrimaryEntity() == null) {
            log.warning("RallyDisruptionPhase.spawnFleet: rally market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                sourceMarket, FleetTypes.PATROL_SMALL,
                combatFPPerFleet, 0f, 0f, 0f, 0f, 0f, 0f);
        params.factionId = initiatorFactionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("RallyDisruptionPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        // Determine index
        int index = 0;
        String routeSourceId = route.getSource() != null ? route.getSource().toString() : "";
        for (int i = 0; i < routeSources.size(); i++) {
            if (routeSources.get(i).equals(routeSourceId)) {
                index = i;
                break;
            }
        }

        created.setName(initiatorSubfactionName + " Disruptors " + (index + 1));
        IntrigueFleetUtil.tagIntrigueFleet(created, initiatorSubfactionName);
        created.getMemoryWithoutUpdate().set("$intrigueMischiefFleet", true);

        // Transponder on — they're being brazen about it
        created.setTransponderOn(true);

        // Don't get sidetracked chasing other fleets or picking fights
        IntrigueFleetUtil.makeFocused(created);

        // Give them emergency burn so they can flee from the big rally fleets
        if (!created.hasAbility(Abilities.EMERGENCY_BURN)) {
            created.addAbility(Abilities.EMERGENCY_BURN);
        }

        created.addEventListener(this);

        // Let the route AI handle placement and base assignment chain
        // Route segments: travel from source to rally → loiter/harass at rally
        // The retargetDisruptors() method in advance() dynamically overrides
        // assignments for flee/chase behavior
        created.addScript(new IntrigueRouteAssignmentAI(created, route,
                "Heading to " + victimSubfactionName + "'s rally",
                "Looking for " + victimSubfactionName + "'s rally fleets to heckle"));

        // Attach proximity-based hostile music
        String musicId = HostileProximityMusicScript.getHostileMusicIdForFaction(initiatorFactionId);
        created.addScript(new HostileProximityMusicScript(created, musicId));

        if (fleets == null) fleets = new ArrayList<>();
        fleets.add(created);

        log.info("RallyDisruptionPhase.spawnFleet: spawned disruptor #" + (index + 1)
                + " (" + combatFPPerFleet + " FP) at " + rallyMarket.getName()
                + " [music: " + musicId + "]");
        return created;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        log.info("RallyDisruptionPhase: a fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null) return;
        if (!fleet.getMemoryWithoutUpdate().getBoolean("$intrigueMischiefFleet")) return;
        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            if (fleets != null) fleets.remove(fleet);
            log.info("RallyDisruptionPhase: disruptor fleet defeated in battle.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null) return;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;
        if (fleets != null) fleets.remove(fleet);
        log.info("RallyDisruptionPhase: fleet despawned. Reason: " + reason);
    }

    // ── OpPhase ─────────────────────────────────────────────────────────

    @Override
    public boolean isDone() { return done; }

    @Override
    public String getStatus() {
        if (!routesStarted) return "Warming up the loudspeakers";
        if (done) return "Had their fun";
        int active = fleets != null ? (int) fleets.stream().filter(f -> f != null && f.isAlive()).count() : 0;
        return "Heckling " + victimSubfactionName + "'s rally (" + active + "/" + FLEET_COUNT + " disruptors)";
    }
}

