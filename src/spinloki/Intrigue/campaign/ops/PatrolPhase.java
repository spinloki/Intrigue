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
 * Phase that sends a patrol fleet from the subfaction's home market to roam a
 * target system and return.
 *
 * <p>Uses {@link RouteManager} so the fleet is only spawned when the player is
 * nearby (within ~1.6 LY). When the player is far away the route advances
 * abstractly and the patrol is treated as a success when the route expires.
 * If the player is nearby and the fleet is spawned, it can be engaged and
 * destroyed, which counts as failure.</p>
 */
public class PatrolPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(PatrolPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_patrol_";
    private final String factionId;
    private final String sourceMarketId;
    private final int combatFP;
    private final float patrolDays;
    private final String subfactionName;
    private boolean done = false;
    private boolean succeeded = false;
    private boolean routeStarted = false;
    private transient CampaignFleetAPI fleet;
    private String routeSource;
    public PatrolPhase(String factionId, String sourceMarketId,
                       int combatFP, float patrolDays, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.combatFP = combatFP;
        this.patrolDays = patrolDays;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }
    @Override
    public void advance(float days) {
        if (done) return;
        if (!PhaseUtil.isSectorAvailable()) {
            if (!routeStarted) {
                log.info("PatrolPhase: no sector (sim mode); auto-completing as success.");
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
            log.info("PatrolPhase: route expired (abstract patrol complete). Success.");
            succeeded = true;
            done = true;
        }
    }
    private void startRoute() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("PatrolPhase: source market missing; aborting.");
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
        route.addSegment(new RouteSegment(1, patrolDays, sourceEntity));
        routeStarted = true;
        log.info("PatrolPhase: registered route '" + routeSource + "' at " + source.getName()
                + " for " + patrolDays + " days (" + combatFP + " FP).");
    }
    private RouteData findOurRoute() {
        if (routeSource == null) return null;
        for (RouteData rd : RouteManager.getInstance().getRoutesForSource(routeSource)) {
            return rd;
        }
        return null;
    }
    // ── RouteFleetSpawner (called by RouteManager when player is nearby) ──
    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI source = route.getMarket();
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("PatrolPhase.spawnFleet: source market missing.");
            return null;
        }
        FleetParamsV3 params = new FleetParamsV3(
                source, FleetTypes.PATROL_LARGE,
                combatFP, 0f, 0f, 0f, 0f, 0f, 0f);
        params.factionId = factionId;
        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("PatrolPhase.spawnFleet: failed to create fleet.");
            return null;
        }
        fleet = created;
        fleet.setName(subfactionName + " Patrol");
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intriguePatrol", true);
        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        fleet.addEventListener(this);
        float remainingDays = patrolDays;
        RouteSegment current = route.getCurrent();
        if (current != null) {
            remainingDays = Math.max(1f, current.daysMax - current.elapsed);
        }
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, sourceEntity, remainingDays,
                "Patrolling on behalf of " + subfactionName);
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                "Returning home");
        log.info("PatrolPhase.spawnFleet: spawned patrol (" + combatFP + " FP) at "
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
        log.info("PatrolPhase: fleet despawned by RouteManager (player moved away).");
    }
    // ── FleetEventListener ──
    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            succeeded = false;
            done = true;
            removeRoute();
            log.info("PatrolPhase: patrol fleet defeated in battle.");
        }
    }
    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return; // RouteManager handling
        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            succeeded = false;
        } else {
            succeeded = true;
        }
        done = true;
        removeRoute();
        log.info("PatrolPhase: fleet despawned. Reason: " + reason + ", succeeded: " + succeeded);
    }
    private void removeRoute() {
        RouteData route = findOurRoute();
        if (route != null) RouteManager.getInstance().removeRoute(route);
    }
    // ── OpPhase ──
    @Override public boolean isDone() { return done; }
    @Override
    public String getStatus() {
        if (!routeStarted) return "Preparing patrol fleet";
        if (done) return succeeded ? "Patrol complete" : "Patrol fleet destroyed";
        return "Patrolling";
    }
    public boolean didSucceed() { return succeeded; }
}
