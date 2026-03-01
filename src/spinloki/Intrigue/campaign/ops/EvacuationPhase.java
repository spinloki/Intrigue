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
import spinloki.Intrigue.campaign.IntrigueFleetUtil;

import java.io.Serializable;
import java.util.logging.Logger;

/**
 * A visual-only phase that spawns a small evacuation fleet retreating from a
 * territory back to the home market. Completes when the route expires
 * (fleet reaches home or the abstract timer runs out).
 *
 * <p>The fleet is unarmed freighters with a small escort — it's a retreat,
 * not an attack. If the player destroys it, the phase still completes
 * (the expulsion is a fait accompli).</p>
 */
public class EvacuationPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(EvacuationPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_evac_";

    private final String factionId;
    private final String sourceMarketId;   // territory base market (where they're leaving from)
    private final String destMarketId;     // home market (where they're retreating to)
    private final int combatFP;
    private final String subfactionName;

    private boolean done = false;
    private boolean routeStarted = false;
    private String routeSource;
    private transient CampaignFleetAPI fleet;

    /**
     * @param factionId       the faction that owns the fleet
     * @param sourceMarketId  the market in the territory the fleet departs from
     * @param destMarketId    the home market the fleet retreats to
     * @param combatFP        small escort FP (retreat fleet, not a war fleet)
     * @param subfactionName  display name for the fleet
     */
    public EvacuationPhase(String factionId, String sourceMarketId, String destMarketId,
                           int combatFP, String subfactionName) {
        this.factionId = factionId;
        this.sourceMarketId = sourceMarketId;
        this.destMarketId = destMarketId;
        this.combatFP = combatFP;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    @Override
    public void advance(float days) {
        if (done) return;

        // Sim mode: auto-complete
        if (!PhaseUtil.isSectorAvailable()) {
            done = true;
            return;
        }

        if (!routeStarted) {
            startRoute();
            return;
        }

        RouteData route = findOurRoute();
        if (route == null && !done) {
            // Route expired — evacuation complete (abstractly arrived home)
            log.info("EvacuationPhase: route expired, evacuation complete.");
            done = true;
        }
    }

    private void startRoute() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        MarketAPI dest = Global.getSector().getEconomy().getMarket(destMarketId);

        if (source == null || dest == null
                || source.getPrimaryEntity() == null || dest.getPrimaryEntity() == null) {
            log.warning("EvacuationPhase: source or dest market missing; auto-completing.");
            done = true;
            return;
        }

        routeSource = ROUTE_SOURCE_PREFIX + factionId + "_" + Global.getSector().getClock().getTimestamp();

        OptionalFleetData extra = new OptionalFleetData(source, factionId);
        extra.fp = (float) combatFP;
        extra.fleetType = FleetTypes.SUPPLY_FLEET;

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        SectorEntityToken destEntity = dest.getPrimaryEntity();

        RouteData route = RouteManager.getInstance().addRoute(
                routeSource, source, (long) (Math.random() * Long.MAX_VALUE),
                extra, this);

        // Travel from territory base to home market, then despawn
        route.addSegment(new RouteSegment(1, sourceEntity, destEntity));

        routeStarted = true;
        log.info("EvacuationPhase: registered evacuation route from "
                + source.getName() + " to " + dest.getName()
                + " (" + combatFP + " FP).");
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
            log.warning("EvacuationPhase.spawnFleet: source market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source,
                FleetTypes.SUPPLY_FLEET,
                combatFP,       // small combat escort
                combatFP * 2,   // mostly freighters — they're hauling their stuff out
                0f, 0f, 0f, 0f, 0f);
        params.factionId = factionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("EvacuationPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        fleet = created;
        fleet.setName(subfactionName + " Evacuation Convoy");
        IntrigueFleetUtil.tagIntrigueFleet(fleet, subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueEvacuation", true);
        IntrigueFleetUtil.makeFocused(fleet);
        fleet.addEventListener(this);

        // Let the route AI handle placement and travel
        fleet.addScript(new IntrigueRouteAssignmentAI(fleet, route,
                "Evacuating from territory",
                "Retreating to " + subfactionName + " home territory"));

        log.info("EvacuationPhase.spawnFleet: spawned evacuation convoy ("
                + combatFP + " FP) at " + source.getName());
        return fleet;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        fleet = null;
        log.info("EvacuationPhase: fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            // Evacuation fleet destroyed — the expulsion still happens, they just
            // didn't get their stuff out. Complete the phase.
            done = true;
            removeRoute();
            log.info("EvacuationPhase: evacuation fleet destroyed. Expulsion proceeds.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;
        done = true;
        removeRoute();
    }

    private void removeRoute() {
        RouteData route = findOurRoute();
        if (route != null) RouteManager.getInstance().removeRoute(route);
    }

    // ── OpPhase ──────────────────────────────────────────────────────────

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!routeStarted) return "Preparing evacuation";
        if (done) return "Evacuation complete";
        return "Evacuating from territory";
    }
}

