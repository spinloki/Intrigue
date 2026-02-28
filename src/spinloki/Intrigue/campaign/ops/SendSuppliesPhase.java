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
 * Phase that sends a supply convoy fleet from the subfaction's home market.
 *
 * <p>Uses {@link RouteManager} so the fleet is only physically spawned when the
 * player is nearby (within ~1.6 LY). When the player is far away the route
 * advances abstractly and the convoy is treated as a success when the route
 * expires. If the player is nearby and the fleet is spawned, it can be engaged
 * and destroyed, which counts as failure.</p>
 */
public class SendSuppliesPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final Logger log = Logger.getLogger(SendSuppliesPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_supply_";

    private final String factionId;
    private final String sourceMarketId;
    private final int combatFP;
    private final float travelDays;
    private final String subfactionName;

    private transient CampaignFleetAPI fleet;
    private String routeSource;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean routeStarted = false;

    public SendSuppliesPhase(String factionId, String sourceMarketId,
                             int combatFP, float travelDays, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.combatFP = combatFP;
        this.travelDays = travelDays;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    @Override
    public void advance(float days) {
        if (done) return;

        // ── Sim mode (no sector) — auto-complete as success ──
        if (!PhaseUtil.isSectorAvailable()) {
            if (!routeStarted) {
                log.info("SendSuppliesPhase: no sector (sim mode); auto-completing as success.");
                succeeded = true;
                done = true;
            }
            return;
        }

        // ── Register the route once ──
        if (!routeStarted) {
            startRoute();
            return;
        }

        // ── Check if the route has expired (abstract completion) ──
        RouteData route = findOurRoute();
        if (route == null && !done) {
            log.info("SendSuppliesPhase: route expired (abstract convoy complete). Success.");
            succeeded = true;
            done = true;
        }
    }

    private void startRoute() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("SendSuppliesPhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        routeSource = ROUTE_SOURCE_PREFIX + sourceMarketId + "_" + Global.getSector().getClock().getTimestamp();

        OptionalFleetData extra = new OptionalFleetData(source, factionId);
        extra.fp = (float) combatFP;
        extra.fleetType = FleetTypes.SUPPLY_FLEET;

        SectorEntityToken sourceEntity = source.getPrimaryEntity();

        RouteData route = RouteManager.getInstance().addRoute(
                routeSource, source, (long) (Math.random() * Long.MAX_VALUE),
                extra, this);

        // Single segment: deliver supplies at/around the source market for travelDays
        route.addSegment(new RouteSegment(1, travelDays, sourceEntity));

        routeStarted = true;
        log.info("SendSuppliesPhase: registered route '" + routeSource + "' at " + source.getName()
                + " for " + travelDays + " days (" + combatFP + " FP).");
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
            log.warning("SendSuppliesPhase.spawnFleet: source market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source,
                FleetTypes.SUPPLY_FLEET,
                combatFP,    // combat escort
                combatFP,    // freighter points
                0f, 0f, 0f, 0f, 0f
        );
        params.factionId = factionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("SendSuppliesPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        fleet = created;
        fleet.setName(subfactionName + " Supply Convoy");

        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueSupplyConvoy", true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);

        fleet.addEventListener(this);

        float remainingDays = travelDays;
        RouteSegment current = route.getCurrent();
        if (current != null) {
            remainingDays = Math.max(1f, current.daysMax - current.elapsed);
        }

        // Travel out, patrol briefly, then return and despawn
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, sourceEntity, remainingDays,
                "Delivering supplies for " + subfactionName);
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                "Returning home");

        log.info("SendSuppliesPhase.spawnFleet: spawned convoy (" + combatFP + " FP) at "
                + source.getName() + " for " + remainingDays + " days.");

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
        log.info("SendSuppliesPhase: fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;

        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            succeeded = false;
            done = true;
            removeRoute();
            log.info("SendSuppliesPhase: convoy defeated in battle.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;

        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) {
            // RouteManager despawning — route continues abstractly
            return;
        }

        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            succeeded = false;
        } else {
            succeeded = true;
        }
        done = true;
        removeRoute();
        log.info("SendSuppliesPhase: convoy despawned. Reason: " + reason
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
        if (!routeStarted) return "Preparing supply convoy";
        if (done) return succeeded ? "Supplies delivered" : "Convoy destroyed";
        return "Convoy en route";
    }

    public boolean didSucceed() {
        return succeeded;
    }
}

