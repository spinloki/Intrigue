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

import java.io.Serializable;
import java.util.logging.Logger;

/**
 * Phase that sends a rally fleet from the subfaction's home market to parade
 * around the home system, drumming up support and consolidating cohesion.
 *
 * <p>Functionally identical to {@link PatrolPhase} but with rally-themed
 * flavor text. Uses {@link RouteManager} so the fleet is only spawned when
 * the player is nearby. When far away, the route advances abstractly and the
 * rally is treated as a success.</p>
 *
 * <p>Fleet name and assignment text vary by faction flavor:</p>
 * <ul>
 *   <li>Hegemony: "Loyalty Parade"</li>
 *   <li>Tri-Tachyon: "Corporate Envoy"</li>
 *   <li>Church: "Revival Procession"</li>
 *   <li>Pirates/Pathers: "Show of Force"</li>
 *   <li>Default: "Rally Fleet"</li>
 * </ul>
 */
public class RallyPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(RallyPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_rally_";

    private final String factionId;
    private final String sourceMarketId;
    private final int combatFP;
    private final float rallyDays;
    private final String subfactionName;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean routeStarted = false;
    private transient CampaignFleetAPI fleet;
    private String routeSource;

    public RallyPhase(String factionId, String sourceMarketId,
                      int combatFP, float rallyDays, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.combatFP = combatFP;
        this.rallyDays = rallyDays;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    // ── Flavor text helpers ─────────────────────────────────────────────

    private String getFleetName() {
        if (factionId == null) return subfactionName + " Rally Fleet";
        switch (factionId) {
            case "hegemony":       return subfactionName + " Loyalty Parade";
            case "tritachyon":     return subfactionName + " Corporate Envoy";
            case "luddic_church":  return subfactionName + " Revival Procession";
            case "luddic_path":
            case "pirates":        return subfactionName + " Show of Force";
            default:               return subfactionName + " Rally Fleet";
        }
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

    // ── Phase lifecycle ─────────────────────────────────────────────────

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!routeStarted) {
                log.info("RallyPhase: no sector (sim mode); auto-completing as success.");
                succeeded = true;
                done = true;
            }
            return;
        }

        if (!routeStarted) {
            startRoute();
            return;
        }

        RouteData route = findOurRoute();
        if (route == null && !done) {
            log.info("RallyPhase: route expired (abstract rally complete). Success.");
            succeeded = true;
            done = true;
        }
    }

    private void startRoute() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("RallyPhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        routeSource = ROUTE_SOURCE_PREFIX + sourceMarketId + "_" + Global.getSector().getClock().getTimestamp();

        OptionalFleetData extra = new OptionalFleetData(source, factionId);
        extra.fp = (float) combatFP;
        extra.fleetType = FleetTypes.PATROL_LARGE;

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        RouteData route = RouteManager.getInstance().addRoute(
                routeSource, source, (long) (Math.random() * Long.MAX_VALUE),
                extra, this);
        route.addSegment(new RouteSegment(1, rallyDays, sourceEntity));

        routeStarted = true;
        log.info("RallyPhase: registered route '" + routeSource + "' at " + source.getName()
                + " for " + rallyDays + " days (" + combatFP + " FP).");
    }

    private RouteData findOurRoute() {
        if (routeSource == null) return null;
        for (RouteData rd : RouteManager.getInstance().getRoutesForSource(routeSource)) {
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
                combatFP, 0f, 0f, 0f, 0f, 0f, 0f);
        params.factionId = factionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("RallyPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        fleet = created;
        fleet.setName(getFleetName());
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueRally", true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        fleet.addEventListener(this);

        float remainingDays = rallyDays;
        RouteSegment current = route.getCurrent();
        if (current != null) {
            remainingDays = Math.max(1f, current.daysMax - current.elapsed);
        }

        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, sourceEntity, remainingDays,
                getAssignmentText());
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                "Returning home");

        log.info("RallyPhase.spawnFleet: spawned rally fleet (" + combatFP + " FP) at "
                + source.getName() + " for " + remainingDays + " days.");
        return fleet;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        fleet = null;
        log.info("RallyPhase: fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            succeeded = false;
            done = true;
            removeRoute();
            log.info("RallyPhase: rally fleet defeated in battle.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;
        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            succeeded = false;
        } else {
            succeeded = true;
        }
        done = true;
        removeRoute();
        log.info("RallyPhase: fleet despawned. Reason: " + reason + ", succeeded: " + succeeded);
    }

    private void removeRoute() {
        RouteData route = findOurRoute();
        if (route != null) RouteManager.getInstance().removeRoute(route);
    }

    // ── OpPhase ─────────────────────────────────────────────────────────

    @Override
    public boolean isDone() { return done; }

    @Override
    public String getStatus() {
        if (!routeStarted) return "Preparing rally fleet";
        if (done) return succeeded ? "Rally complete" : "Rally fleet destroyed";
        return "Rallying";
    }

    public boolean didSucceed() { return succeeded; }
}

