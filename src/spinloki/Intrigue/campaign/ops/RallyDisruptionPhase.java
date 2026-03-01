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
import spinloki.Intrigue.campaign.HostileProximityMusicScript;

import java.io.Serializable;
import java.util.logging.Logger;

/**
 * Phase for mischief ops targeting rallies: spawns a small fleet belonging
 * to the mischief initiator that flies around the rally location, disrupting
 * the rally. The fleet's assignment text reads "Disrupting [target]'s rally."
 *
 * <p>When the player is nearby and the fleet is spawned, a
 * {@link HostileProximityMusicScript} is attached so the mischief faction's
 * hostile encounter music plays with proximity-based volume falloff.</p>
 *
 * <p>If the fleet is never spawned (player is far away or sim mode), the
 * phase auto-completes abstractly.</p>
 */
public class RallyDisruptionPhase implements OpPhase, RouteFleetSpawner, FleetEventListener, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(RallyDisruptionPhase.class.getName());
    private static final String ROUTE_SOURCE_PREFIX = "intrigue_mischief_rally_";

    /** How long the mischief fleet harasses the rally. */
    private static final float DISRUPTION_DAYS = 10f;
    /** Base FP for the mischief disruption fleet (small nuisance fleet). */
    private static final int BASE_FP = 8;

    private final String initiatorFactionId;
    private final String initiatorSubfactionName;
    private final String victimSubfactionName;
    private final String rallyMarketId;
    private final int combatFP;

    private boolean done = false;
    private boolean routeStarted = false;
    private transient CampaignFleetAPI fleet;
    private String routeSource;

    /**
     * @param initiatorFactionId      base faction ID of the mischief initiator
     * @param initiatorSubfactionName display name of the mischief initiator
     * @param victimSubfactionName    display name of the rally's subfaction (for flavor text)
     * @param rallyMarketId           market ID where the rally is taking place
     * @param initiatorCohesion       initiator's home cohesion (scales fleet size)
     */
    public RallyDisruptionPhase(String initiatorFactionId,
                                String initiatorSubfactionName,
                                String victimSubfactionName,
                                String rallyMarketId,
                                int initiatorCohesion) {
        this.initiatorFactionId = initiatorFactionId;
        this.initiatorSubfactionName = initiatorSubfactionName;
        this.victimSubfactionName = victimSubfactionName;
        this.rallyMarketId = rallyMarketId;
        // Small fleet, slightly stronger with higher cohesion
        this.combatFP = BASE_FP + (int) (initiatorCohesion * 0.1f);
    }

    // ── Phase lifecycle ─────────────────────────────────────────────────

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            if (!routeStarted) {
                log.info("RallyDisruptionPhase: no sector (sim mode); auto-completing.");
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
            log.info("RallyDisruptionPhase: route expired (abstract disruption complete).");
            done = true;
        }
    }

    private void startRoute() {
        MarketAPI rallyMarket = Global.getSector().getEconomy().getMarket(rallyMarketId);
        if (rallyMarket == null || rallyMarket.getPrimaryEntity() == null) {
            log.warning("RallyDisruptionPhase: rally market missing; aborting.");
            done = true;
            return;
        }

        routeSource = ROUTE_SOURCE_PREFIX + rallyMarketId + "_"
                + Global.getSector().getClock().getTimestamp();

        OptionalFleetData extra = new OptionalFleetData(rallyMarket, initiatorFactionId);
        extra.fp = (float) combatFP;
        extra.fleetType = FleetTypes.PATROL_SMALL;

        SectorEntityToken rallyEntity = rallyMarket.getPrimaryEntity();
        RouteData route = RouteManager.getInstance().addRoute(
                routeSource, rallyMarket, (long) (Math.random() * Long.MAX_VALUE),
                extra, this);
        route.addSegment(new RouteSegment(1, DISRUPTION_DAYS, rallyEntity));

        routeStarted = true;
        log.info("RallyDisruptionPhase: registered disruption route at "
                + rallyMarket.getName() + " for " + DISRUPTION_DAYS + " days ("
                + combatFP + " FP).");
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
        MarketAPI rallyMarket = route.getMarket();
        if (rallyMarket == null || rallyMarket.getPrimaryEntity() == null) {
            log.warning("RallyDisruptionPhase.spawnFleet: rally market missing.");
            return null;
        }

        FleetParamsV3 params = new FleetParamsV3(
                rallyMarket, FleetTypes.PATROL_SMALL,
                combatFP, 0f, 0f, 0f, 0f, 0f, 0f);
        params.factionId = initiatorFactionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("RallyDisruptionPhase.spawnFleet: failed to create fleet.");
            return null;
        }

        fleet = created;
        fleet.setName(initiatorSubfactionName + " Disruptors");
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", initiatorSubfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueMischiefFleet", true);

        SectorEntityToken rallyEntity = rallyMarket.getPrimaryEntity();
        rallyEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(rallyEntity.getLocation().x, rallyEntity.getLocation().y);
        fleet.addEventListener(this);

        // Transponder off — they're causing trouble
        fleet.setTransponderOn(false);

        float remainingDays = DISRUPTION_DAYS;
        RouteSegment current = route.getCurrent();
        if (current != null) {
            remainingDays = Math.max(1f, current.daysMax - current.elapsed);
        }

        String assignmentText = "Disrupting " + victimSubfactionName + "'s rally";
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, rallyEntity, remainingDays,
                assignmentText);
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, rallyEntity, 120f,
                "Withdrawing");

        // ── Attach proximity-based hostile music ────────────────────────
        String musicId = HostileProximityMusicScript.getHostileMusicIdForFaction(initiatorFactionId);
        fleet.addScript(new HostileProximityMusicScript(fleet, musicId));

        log.info("RallyDisruptionPhase.spawnFleet: spawned mischief fleet ("
                + combatFP + " FP) at " + rallyMarket.getName()
                + " — disrupting " + victimSubfactionName + "'s rally"
                + " [music: " + musicId + "]");
        return fleet;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) { return false; }

    @Override
    public boolean shouldRepeat(RouteData route) { return false; }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        fleet = null;
        log.info("RallyDisruptionPhase: fleet despawned by RouteManager (player moved away).");
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            done = true;
            removeRoute();
            log.info("RallyDisruptionPhase: mischief fleet defeated in battle.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null || fleet != this.fleet) return;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) return;
        done = true;
        removeRoute();
        log.info("RallyDisruptionPhase: fleet despawned. Reason: " + reason);
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
        if (!routeStarted) return "Preparing disruption fleet";
        if (done) return "Disruption complete";
        return "Disrupting " + victimSubfactionName + "'s rally";
    }
}

