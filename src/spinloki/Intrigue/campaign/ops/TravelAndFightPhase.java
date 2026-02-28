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

import java.util.logging.Logger;

/**
 * Phase 2 of a RaidOp: send a fleet from the initiator's home market to the
 * target market, attack, and return.
 *
 * <p>Uses {@link RouteManager} so the fleet is only spawned when the player is
 * nearby (within ~1.6 LY of the route's interpolated position). When the player
 * is far away, the route advances abstractly. If the route expires without the
 * fleet being spawned, the raid is resolved abstractly as a win (the defender
 * didn't get reinforced by the player).</p>
 *
 * <p>If the fleet IS spawned and the player engages it, battle results determine
 * the outcome.</p>
 */
public class TravelAndFightPhase implements OpPhase, RouteFleetSpawner, FleetEventListener {

    private static final Logger log = Logger.getLogger(TravelAndFightPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_raid_";

    private final String initiatorFactionId;
    private final String sourceMarketId;
    private final String targetMarketId;
    private final int combatFP;
    private final String subfactionName;

    private boolean done = false;
    private boolean fleetWon = false;

    /** True once we've registered a route with RouteManager (or auto-completed in sim mode). */
    private boolean routeStarted = false;

    /** The fleet currently spawned by RouteManager. Transient. */
    private transient CampaignFleetAPI fleet;

    /** Unique source key for this route in RouteManager. */
    private String routeSource;

    /**
     * @param initiatorFactionId faction that owns the fleet
     * @param sourceMarketId     market where the fleet spawns
     * @param targetMarketId     market to attack
     * @param combatFP           fleet points for combat ships
     * @param subfactionName     display name of the subfaction (for fleet labeling)
     */
    public TravelAndFightPhase(String initiatorFactionId, String sourceMarketId,
                               String targetMarketId, int combatFP, String subfactionName) {
        this.initiatorFactionId = initiatorFactionId;
        this.sourceMarketId = sourceMarketId;
        this.targetMarketId = targetMarketId;
        this.combatFP = combatFP;
        this.subfactionName = subfactionName != null ? subfactionName : "Intrigue";
    }

    @Override
    public void advance(float days) {
        if (done) return;

        // ── Sim mode (no sector) — auto-complete ──
        if (!isSectorAvailable()) {
            if (!routeStarted) {
                log.info("TravelAndFightPhase: no sector available (sim mode); auto-completing.");
                fleetWon = false;
                done = true;
            }
            return;
        }

        // ── Register the route once ──
        if (!routeStarted) {
            startRoute();
            return;
        }

        // ── Check if the route has expired ──
        RouteData route = findOurRoute();
        if (route == null) {
            if (!done) {
                // Route expired without the fleet being destroyed by player —
                // abstract resolution: the raid succeeded.
                log.info("TravelAndFightPhase: route expired (abstract raid complete). Success.");
                fleetWon = true;
                done = true;
            }
        }
    }

    private void startRoute() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        MarketAPI target = Global.getSector().getEconomy().getMarket(targetMarketId);

        if (source == null || target == null
                || source.getPrimaryEntity() == null || target.getPrimaryEntity() == null) {
            log.warning("TravelAndFightPhase: source or target market missing; aborting.");
            fleetWon = false;
            done = true;
            return;
        }

        routeSource = ROUTE_SOURCE_PREFIX + sourceMarketId + "_" + targetMarketId
                + "_" + Global.getSector().getClock().getTimestamp();

        OptionalFleetData extra = new OptionalFleetData(source, initiatorFactionId);
        extra.fp = (float) combatFP;
        extra.fleetType = FleetTypes.TASK_FORCE;

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        SectorEntityToken targetEntity = target.getPrimaryEntity();

        RouteData route = RouteManager.getInstance().addRoute(
                routeSource, source, (long) (Math.random() * Long.MAX_VALUE),
                extra, this);

        // Segment 1: travel from source to target
        route.addSegment(new RouteSegment(1, sourceEntity, targetEntity));
        // Segment 2: attack at target for 30 days
        route.addSegment(new RouteSegment(2, 30f, targetEntity));
        // Segment 3: return to source
        route.addSegment(new RouteSegment(3, targetEntity, sourceEntity));

        routeStarted = true;
        log.info("TravelAndFightPhase: registered route '" + routeSource + "' from "
                + source.getName() + " to " + target.getName() + " (" + combatFP + " FP).");
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
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        MarketAPI target = Global.getSector().getEconomy().getMarket(targetMarketId);

        if (source == null || target == null
                || source.getPrimaryEntity() == null || target.getPrimaryEntity() == null) {
            log.warning("TravelAndFightPhase.spawnFleet: source or target market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source,
                FleetTypes.TASK_FORCE,
                combatFP,
                0f, 0f, 0f, 0f, 0f, 0f
        );
        params.factionId = initiatorFactionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("TravelAndFightPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        fleet = created;
        fleet.setName(subfactionName + " Raid Fleet");

        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);

        // Place the fleet at the route's current interpolated location
        RouteSegment current = route.getCurrent();
        LocationAPI loc = current != null ? current.getCurrentContainingLocation() : null;
        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        SectorEntityToken targetEntity = target.getPrimaryEntity();

        if (loc != null) {
            loc.addEntity(fleet);
            if (current.isInSystem()) {
                SectorEntityToken dest = current.getDestination();
                fleet.setLocation(dest.getLocation().x, dest.getLocation().y);
            } else {
                fleet.setLocation(route.getInterpolatedHyperLocation().x,
                                  route.getInterpolatedHyperLocation().y);
            }
        } else {
            sourceEntity.getContainingLocation().addEntity(fleet);
            fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);
        }

        fleet.addEventListener(this);

        // Determine remaining assignments based on which segment we're in
        int segIndex = route.getCurrentIndex();
        if (segIndex <= 0) {
            // Still in travel-to-target phase
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, targetEntity, 120f,
                    "Travelling to " + target.getName());
            fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, targetEntity, 30f,
                    "Attacking " + target.getName());
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                    "Returning home");
        } else if (segIndex == 1) {
            // At target — attack phase
            fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, targetEntity, 30f,
                    "Attacking " + target.getName());
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                    "Returning home");
        } else {
            // Returning home
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                    "Returning home");
        }

        log.info("TravelAndFightPhase.spawnFleet: spawned raid fleet (" + combatFP + " FP)"
                + " at segment " + segIndex + " (player nearby).");

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
        log.info("TravelAndFightPhase: fleet despawned by RouteManager (player moved away). Route continues.");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null) return;
        if (fleet != this.fleet) return;

        if (battle.wasFleetVictorious(fleet, primaryWinner)) {
            fleetWon = true;
            log.info("TravelAndFightPhase: raid fleet won a battle.");
        } else if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            fleetWon = false;
            done = true;
            removeRoute();
            log.info("TravelAndFightPhase: raid fleet was defeated.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null) return;
        if (fleet != this.fleet) return;

        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) {
            // RouteManager despawning — route continues abstractly
            return;
        }

        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            fleetWon = false;
        } else if (reason == FleetDespawnReason.REACHED_DESTINATION) {
            if (!fleetWon) fleetWon = true;
        }
        done = true;
        removeRoute();
        log.info("TravelAndFightPhase: fleet despawned. Reason: " + reason + ", won: " + fleetWon);
    }

    private void removeRoute() {
        RouteData route = findOurRoute();
        if (route != null) {
            RouteManager.getInstance().removeRoute(route);
        }
    }

    // ── OpPhase ──────────────────────────────────────────────────────────

    private boolean isSectorAvailable() {
        try {
            return Global.getSector() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!routeStarted) return "Preparing fleet";
        if (done) return fleetWon ? "Fleet victorious" : "Fleet defeated";
        RouteData route = findOurRoute();
        if (route != null) {
            int seg = route.getCurrentIndex();
            if (seg <= 0) return "Fleet en route to target";
            if (seg == 1) return "Fleet attacking target";
            return "Fleet returning home";
        }
        return "Fleet en route / in combat";
    }

    public boolean didFleetWin() {
        return fleetWon;
    }

    public String getFleetId() {
        if (fleet != null) return fleet.getId();
        return null;
    }
}
