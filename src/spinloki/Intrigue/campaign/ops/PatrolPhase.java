package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;

import java.io.Serializable;
import java.util.logging.Logger;

/**
 * Phase that spawns a patrol fleet at the subfaction's home market, sends it
 * to roam a target system (or the home system), and tracks whether it survives.
 *
 * <ul>
 *   <li>If the fleet completes its patrol and despawns normally → <b>success</b></li>
 *   <li>If the fleet is destroyed in battle → <b>failure</b></li>
 * </ul>
 *
 * Implements {@link FleetEventListener} to detect battle outcomes and despawn.
 */
public class PatrolPhase implements OpPhase, FleetEventListener, Serializable {

    private static final Logger log = Logger.getLogger(PatrolPhase.class.getName());

    private final String factionId;
    private final String sourceMarketId;
    private final int combatFP;
    private final float patrolDays;
    private final String subfactionName;

    private transient CampaignFleetAPI fleet;
    private String fleetId;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean fleetSpawned = false;

    /**
     * @param factionId       faction that owns the patrol fleet
     * @param sourceMarketId  market where the fleet spawns (and returns to)
     * @param combatFP        fleet points for the patrol ships
     * @param patrolDays      how many days the fleet should patrol before returning
     * @param subfactionName  display name for fleet labeling
     */
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

        if (!fleetSpawned) {
            if (!isSectorAvailable()) {
                // Sim mode — auto-complete as success (no fleet to destroy)
                log.info("PatrolPhase: no sector available (sim mode); auto-completing as success.");
                succeeded = true;
                done = true;
                return;
            }
            spawnFleet();
            return;
        }

        // Re-acquire fleet reference after save/load
        if (fleet == null && fleetId != null) {
            if (!isSectorAvailable()) {
                succeeded = false;
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
                log.warning("PatrolPhase: fleet " + fleetId + " not found after load; treating as destroyed.");
                succeeded = false;
                done = true;
                return;
            }
        }

        // Fleet emptied (all ships gone but despawn not yet fired)
        if (fleet != null && fleet.isEmpty()) {
            succeeded = false;
            done = true;
        }
    }

    private void spawnFleet() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);

        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("PatrolPhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        FleetParamsV3 params = new FleetParamsV3(
                source,
                FleetTypes.PATROL_LARGE,
                combatFP,
                0f, 0f, 0f, 0f, 0f, 0f
        );
        params.factionId = factionId;

        CampaignFleetAPI created = FleetFactoryV3.createFleet(params);
        if (created == null || created.isEmpty()) {
            log.warning("PatrolPhase: failed to create fleet; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        fleet = created;
        fleet.setName(subfactionName + " Patrol");
        fleet.setNoAutoDespawn(true);

        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intriguePatrol", true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);

        fleet.addEventListener(this);

        // Patrol the system, then return home and despawn
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, sourceEntity, patrolDays,
                "Patrolling on behalf of " + subfactionName);
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                "Returning home");

        fleetId = fleet.getId();
        fleetSpawned = true;

        log.info("PatrolPhase: spawned patrol fleet " + fleetId + " (" + combatFP + " FP) at "
                + source.getName() + " for " + patrolDays + " days");
    }

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
        if (!fleetSpawned) return "Preparing patrol fleet";
        if (done) return succeeded ? "Patrol complete" : "Patrol fleet destroyed";
        return "Patrolling";
    }

    public boolean didSucceed() {
        return succeeded;
    }

    public String getFleetId() {
        return fleetId;
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null) return;
        if (!fleet.getId().equals(this.fleetId)) return;

        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            succeeded = false;
            done = true;
            log.info("PatrolPhase: patrol fleet " + fleetId + " was defeated in battle.");
        }
        // Winning a battle during patrol is normal — patrol continues.
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null) return;
        if (!fleet.getId().equals(this.fleetId)) return;

        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            succeeded = false;
        } else if (reason == FleetDespawnReason.REACHED_DESTINATION) {
            // Completed patrol and returned home
            succeeded = true;
        } else {
            // Other despawn reasons (e.g. OTHER) — treat as success (patrol wasn't destroyed)
            succeeded = true;
        }
        done = true;
        log.info("PatrolPhase: patrol fleet " + fleetId + " despawned. Reason: " + reason
                + ", succeeded: " + succeeded);
    }
}


