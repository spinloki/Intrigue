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
import spinloki.Intrigue.campaign.IntrigueFleetUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Phase that sends multiple large rally fleets from the subfaction's home
 * market to parade around the home system, drumming up support and
 * consolidating cohesion.
 *
 * <p>Spawns {@link #FLEET_COUNT} fleets, each a substantial show of force.
 * When disruptor fleets are nearby, rally fleets react with confused,
 * erratic behavior — emergency burning in odd directions as they're
 * harassed.</p>
 */
public class RallyPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(RallyPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_rally_";

    /** Number of rally fleets to spawn. */
    static final int FLEET_COUNT = 5;

    private final String factionId;
    private final String sourceMarketId;
    private final int combatFPPerFleet;
    private final float rallyDays;
    private final String subfactionName;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean routesStarted = false;
    private final List<String> routeSources = new ArrayList<>();
    private transient List<CampaignFleetAPI> fleets = new ArrayList<>();
    private transient IntervalUtil retargetInterval;
    private int fleetsDestroyed = 0;
    private int fleetsCompleted = 0;

    public RallyPhase(String factionId, String sourceMarketId,
                      int combatFPPerFleet, float rallyDays, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.combatFPPerFleet = combatFPPerFleet;
        this.rallyDays = rallyDays;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    // ── Flavor text helpers ─────────────────────────────────────────────

    private String getFleetName(int index) {
        String base;
        if (factionId == null) base = subfactionName + " Rally Fleet";
        else switch (factionId) {
            case "hegemony":       base = subfactionName + " Loyalty Parade"; break;
            case "tritachyon":     base = subfactionName + " Corporate Envoy"; break;
            case "luddic_church":  base = subfactionName + " Revival Procession"; break;
            case "luddic_path":
            case "pirates":        base = subfactionName + " Show of Force"; break;
            default:               base = subfactionName + " Rally Fleet"; break;
        }
        return base + " " + (index + 1);
    }

    private String getAssignmentText() {
        if (factionId == null) return "Rallying support for " + subfactionName;
        switch (factionId) {
            case "hegemony":       return "Conducting loyalty parade for " + subfactionName;
            case "tritachyon":     return "Corporate goodwill tour for " + subfactionName;
            case "luddic_church":  return "Revival procession for " + subfactionName;
            case "luddic_path":    return "Demonstrating strength for " + subfactionName;
            case "pirates":        return "Show of force for " + subfactionName;
            default:               return "Rallying support for " + subfactionName;
        }
    }

    private String getAnnoyedAssignmentText() {
        if (factionId == null) return "Chasing off obnoxious interlopers";
        switch (factionId) {
            case "hegemony":       return "Trying to maintain formation despite constant buzzing";
            case "tritachyon":     return "Filing harassment complaints while giving chase";
            case "luddic_church":  return "Furiously pursuing the heathens disrupting their procession";
            case "luddic_path":    return "Chasing down the cowards mocking their demonstration";
            case "pirates":        return "Losing their minds trying to catch the little gnats";
            default:               return "Chasing off obnoxious interlopers";
        }
    }

    // ── Phase lifecycle ─────────────────────────────────────────────────

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!routesStarted) {
                log.info("RallyPhase: no sector (sim mode); auto-completing as success.");
                succeeded = true;
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
            // Majority survived = success
            succeeded = fleetsDestroyed < (FLEET_COUNT / 2 + 1);
            done = true;
            log.info("RallyPhase: all routes expired. Destroyed: " + fleetsDestroyed
                    + "/" + FLEET_COUNT + ". " + (succeeded ? "Success." : "Failure."));
        }

        // Make rally fleets react to nearby disruptors
        if (fleets == null) fleets = new ArrayList<>();

        // Always clear stale chasing flags — don't wait for the retarget interval.
        // A rally fleet should only have the flag while actively intercepting.
        for (CampaignFleetAPI fleet : fleets) {
            if (fleet == null || !fleet.isAlive()) continue;
            FleetAssignment currentAssign = fleet.getCurrentAssignment() != null
                    ? fleet.getCurrentAssignment().getAssignment() : null;
            if (currentAssign != FleetAssignment.INTERCEPT
                    && fleet.getMemoryWithoutUpdate().getBoolean("$intrigueRallyChasing")) {
                fleet.getMemoryWithoutUpdate().unset("$intrigueRallyChasing");
                IntrigueFleetUtil.makeFocused(fleet);
            }
        }

        if (retargetInterval == null) retargetInterval = new IntervalUtil(0.3f, 0.8f);
        retargetInterval.advance(days);
        if (retargetInterval.intervalElapsed()) {
            for (CampaignFleetAPI fleet : fleets) {
                if (fleet == null || !fleet.isAlive()) continue;
                reactToDisruptors(fleet);
            }
        }
    }

    /**
     * If disruptor fleets are very close, the rally fleet finally loses
     * patience and breaks formation to chase them off. They tolerate
     * buzzing at range for a long time before snapping.
     */
    private void reactToDisruptors(CampaignFleetAPI rallyFleet) {
        CampaignFleetAPI nearestDisruptor = findNearestDisruptor(rallyFleet);

        if (nearestDisruptor != null) {
            float dist = Misc.getDistance(rallyFleet, nearestDisruptor);
            if (dist < 500f) {
                FleetAssignment currentAssign = rallyFleet.getCurrentAssignment() != null
                        ? rallyFleet.getCurrentAssignment().getAssignment() : null;

                // Mark that we're chasing so disruptors can detect it and flee
                rallyFleet.getMemoryWithoutUpdate().set("$intrigueRallyChasing", true);

                if (currentAssign != FleetAssignment.INTERCEPT) {
                    // Temporarily unfocus so the chase actually works
                    IntrigueFleetUtil.removeFocused(rallyFleet);
                    rallyFleet.clearAssignments();
                    // Pop emergency burn for a threatening lunge
                    activateEmergencyBurn(rallyFleet);
                    rallyFleet.addAssignment(FleetAssignment.INTERCEPT,
                            nearestDisruptor, 0.3f,
                            getAnnoyedAssignmentText());
                    // Fall back to patrol after chasing — re-focus
                    SectorEntityToken home = getHomeEntity();
                    if (home != null) {
                        rallyFleet.addAssignment(FleetAssignment.PATROL_SYSTEM,
                                home, rallyDays,
                                getAssignmentText());
                        rallyFleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN,
                                home, 120f, "Returning home");
                    }
                }
                return;
            }
        }

        // No disruptors close enough — clear the chasing flag and re-focus
        rallyFleet.getMemoryWithoutUpdate().unset("$intrigueRallyChasing");
        IntrigueFleetUtil.makeFocused(rallyFleet);
    }

    private CampaignFleetAPI findNearestDisruptor(CampaignFleetAPI rallyFleet) {
        CampaignFleetAPI nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (CampaignFleetAPI other : rallyFleet.getContainingLocation().getFleets()) {
            if (!other.getMemoryWithoutUpdate().getBoolean("$intrigueMischiefFleet")) continue;
            if (!other.isAlive()) continue;
            float dist = Misc.getDistance(rallyFleet, other);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = other;
            }
        }
        return nearest;
    }

    private SectorEntityToken getHomeEntity() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        return source != null ? source.getPrimaryEntity() : null;
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
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("RallyPhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        long baseSeed = Global.getSector().getClock().getTimestamp();

        for (int i = 0; i < FLEET_COUNT; i++) {
            String rs = ROUTE_SOURCE_PREFIX + sourceMarketId + "_" + baseSeed + "_" + i;

            OptionalFleetData extra = new OptionalFleetData(source, factionId);
            extra.fp = (float) combatFPPerFleet;
            extra.fleetType = FleetTypes.PATROL_LARGE;

            RouteData route = RouteManager.getInstance().addRoute(
                    rs, source, (long) (Math.random() * Long.MAX_VALUE),
                    extra, this);
            route.addSegment(new RouteSegment(1, rallyDays, sourceEntity));
            routeSources.add(rs);
        }

        routesStarted = true;
        if (fleets == null) fleets = new ArrayList<>();
        log.info("RallyPhase: registered " + FLEET_COUNT + " routes at " + source.getName()
                + " for " + rallyDays + " days (" + combatFPPerFleet + " FP each).");
    }

    /**
     * Collect notable entities in the source system for rally fleets to spread across.
     * Includes planets, stations, jump points. Always includes the home market entity.
     * Shuffles the result so fleets get variety.
     */
    private List<SectorEntityToken> collectRallyPoints(SectorEntityToken homeEntity) {
        List<SectorEntityToken> points = new ArrayList<>();
        points.add(homeEntity);

        LocationAPI system = homeEntity.getContainingLocation();
        if (system != null) {
            for (SectorEntityToken entity : system.getAllEntities()) {
                if (entity == homeEntity) continue;
                // Planets, stations, and jump points make good rally targets
                if (entity instanceof com.fs.starfarer.api.campaign.PlanetAPI
                        || entity.hasTag("station")
                        || entity.hasTag("jump_point")
                        || entity.getMarket() != null) {
                    points.add(entity);
                }
            }
        }

        Collections.shuffle(points);
        // Always ensure home is first so at least one fleet stays near home
        points.remove(homeEntity);
        points.add(0, homeEntity);

        return points;
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
        MarketAPI source = route.getMarket();
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("RallyPhase.spawnFleet: source market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source, FleetTypes.PATROL_LARGE,
                combatFPPerFleet, 0f, 0f, 0f, 0f, 0f, 0f);
        params.factionId = factionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("RallyPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        // Determine which index this fleet is
        int index = 0;
        String routeSourceId = route.getSource() != null ? route.getSource().toString() : "";
        for (int i = 0; i < routeSources.size(); i++) {
            if (routeSources.get(i).equals(routeSourceId)) {
                index = i;
                break;
            }
        }

        created.setName(getFleetName(index));
        IntrigueFleetUtil.tagIntrigueFleet(created, subfactionName);
        created.getMemoryWithoutUpdate().set("$intrigueRally", true);

        // Rally fleets are big, slow parade formations
        created.getStats().getFleetwideMaxBurnMod().modifyMult("intrigue_rally_slow", 0.5f);

        // Stay focused on the parade — don't get distracted by random hostiles
        IntrigueFleetUtil.makeFocused(created);

        // Give them emergency burn so they can lunge at disruptors when they snap
        if (!created.hasAbility(Abilities.EMERGENCY_BURN)) {
            created.addAbility(Abilities.EMERGENCY_BURN);
        }

        created.addEventListener(this);

        // Let the route AI handle placement, then build a custom touring
        // parade route through random POIs in the system
        created.addScript(new IntrigueRouteAssignmentAI(created, route,
                "Heading to rally point",
                getAssignmentText()));

        if (fleets == null) fleets = new ArrayList<>();
        fleets.add(created);

        log.info("RallyPhase.spawnFleet: spawned rally fleet #" + (index + 1)
                + " (" + combatFPPerFleet + " FP) at " + source.getName()
                + " for " + rallyDays + " days.");
        return created;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        log.info("RallyPhase: a fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null) return;
        if (!fleet.getMemoryWithoutUpdate().getBoolean("$intrigueRally")) return;
        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            fleetsDestroyed++;
            if (fleets != null) fleets.remove(fleet);
            log.info("RallyPhase: rally fleet destroyed. (" + fleetsDestroyed + "/" + FLEET_COUNT + ")");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null) return;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;
        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            // Already handled in reportBattleOccurred
            return;
        }
        fleetsCompleted++;
        if (fleets != null) fleets.remove(fleet);
        log.info("RallyPhase: fleet despawned. Reason: " + reason
                + " (completed: " + fleetsCompleted + "/" + FLEET_COUNT + ")");
    }

    // ── OpPhase ─────────────────────────────────────────────────────────

    @Override
    public boolean isDone() { return done; }

    @Override
    public String getStatus() {
        if (!routesStarted) return "Preparing rally fleets";
        if (done) return succeeded ? "Rally complete" : "Rally fleets overwhelmed";
        int active = FLEET_COUNT - fleetsDestroyed - fleetsCompleted;
        return "Rallying (" + active + "/" + FLEET_COUNT + " fleets active)";
    }

    public boolean didSucceed() { return succeeded; }
}

