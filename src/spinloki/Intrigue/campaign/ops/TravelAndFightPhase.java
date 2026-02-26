package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;

import java.util.logging.Logger;

/**
 * Phase 2 of a RaidOp: spawn a fleet, send it to the target, and track the battle.
 *
 * The fleet is spawned at the initiator's home market, given a GO_TO_LOCATION
 * assignment toward the target market's primary entity, then ATTACK_LOCATION.
 * We listen for battle events and despawn events to determine the outcome.
 *
 * This phase is done when the fleet either wins, loses, or despawns.
 */
public class TravelAndFightPhase implements OpPhase, FleetEventListener {

    private static final Logger log = Logger.getLogger(TravelAndFightPhase.class.getName());

    private final String initiatorFactionId;
    private final String sourceMarketId;
    private final String targetMarketId;
    private final int combatFP;
    private final String subfactionName;

    // Transient: fleet reference is not serializable; re-lookup via fleetId on load.
    private transient CampaignFleetAPI fleet;
    private String fleetId;

    private boolean done = false;
    private boolean fleetWon = false;
    private boolean fleetSpawned = false;

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

        if (!fleetSpawned) {
            // In sim mode (no sector), auto-complete as a loss.
            if (!isSectorAvailable()) {
                log.info("TravelAndFightPhase: no sector available (sim mode); auto-completing.");
                fleetWon = false;
                done = true;
                return;
            }
            spawnFleet();
            return;
        }

        // Re-acquire fleet reference after save/load
        if (fleet == null && fleetId != null) {
            if (!isSectorAvailable()) {
                fleetWon = false;
                done = true;
                return;
            }
            for (LocationAPI loc : Global.getSector().getAllLocations()) {
                for (CampaignFleetAPI f : loc.getFleets()) {
                    if (fleetId.equals(f.getId())) {
                        fleet = f;
                        break;
                    }
                }
                if (fleet != null) break;
            }
            if (fleet == null) {
                // Fleet no longer exists — treat as loss
                log.warning("TravelAndFightPhase: fleet " + fleetId + " not found after load; treating as loss.");
                fleetWon = false;
                done = true;
                return;
            }
        }

        // If fleet is empty (all ships destroyed but despawn event not yet fired), end it
        if (fleet != null && fleet.isEmpty()) {
            fleetWon = false;
            done = true;
        }
    }

    private void spawnFleet() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);
        MarketAPI target = Global.getSector().getEconomy().getMarket(targetMarketId);

        if (source == null || target == null || source.getPrimaryEntity() == null || target.getPrimaryEntity() == null) {
            log.warning("TravelAndFightPhase: source or target market missing; aborting.");
            fleetWon = false;
            done = true;
            return;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source,
                FleetTypes.TASK_FORCE,
                combatFP, // combatPts
                0f,       // freighterPts
                0f,       // tankerPts
                0f,       // transportPts
                0f,       // linerPts
                0f,       // utilityPts
                0f        // qualityMod
        );
        params.factionId = initiatorFactionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("TravelAndFightPhase: failed to create fleet; aborting.");
            fleetWon = false;
            done = true;
            return;
        }

        fleet = created;
        fleet.setName(subfactionName + " Raid Fleet");
        fleet.setNoAutoDespawn(true);

        // Tag the fleet so other systems can identify it as an intrigue fleet
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);

        // Place at source market
        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);

        // Listen for events on this fleet
        fleet.addEventListener(this);

        // Assignments: travel to target, then attack
        SectorEntityToken targetEntity = target.getPrimaryEntity();
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, targetEntity, 120f, "Travelling to " + target.getName());
        fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, targetEntity, 30f, "Attacking " + target.getName());
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f, "Returning home");

        fleetId = fleet.getId();
        fleetSpawned = true;

        log.info("TravelAndFightPhase: spawned fleet " + fleetId + " (" + combatFP + " FP) at " +
                 source.getName() + " targeting " + target.getName());
    }

    /**
     * Check if the Starsector sector is available. Returns false in sim/test mode.
     */
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
        if (!fleetSpawned) return "Preparing fleet";
        if (done) return fleetWon ? "Fleet victorious" : "Fleet defeated";
        return "Fleet en route / in combat";
    }

    public boolean didFleetWin() {
        return fleetWon;
    }

    public String getFleetId() {
        return fleetId;
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null) return;
        if (!fleet.getId().equals(this.fleetId)) return;

        if (battle.wasFleetVictorious(fleet, primaryWinner)) {
            fleetWon = true;
            log.info("TravelAndFightPhase: fleet " + fleetId + " won a battle.");
        } else if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            fleetWon = false;
            done = true;
            log.info("TravelAndFightPhase: fleet " + fleetId + " was defeated.");
        }
        // If neither won nor defeated (ongoing multi-stage), keep going.
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null) return;
        if (!fleet.getId().equals(this.fleetId)) return;

        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            fleetWon = false;
        } else if (reason == FleetDespawnReason.REACHED_DESTINATION) {
            // Fleet returned home after completing its mission.
            // fleetWon was already set by reportBattleOccurred if a battle happened;
            // if no battle occurred (target empty/friendly), count as success.
            if (!fleetWon) fleetWon = true;
        }
        done = true;
        log.info("TravelAndFightPhase: fleet " + fleetId + " despawned. Reason: " + reason +
                 ", won: " + fleetWon);
    }
}


