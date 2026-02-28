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
 * Phase that spawns a supply convoy fleet. The fleet travels from the home
 * market, hangs around for a set duration, then returns and despawns.
 *
 * If the fleet is destroyed → failure. If it completes the run → success.
 */
public class SendSuppliesPhase implements OpPhase, FleetEventListener, Serializable {

    private static final Logger log = Logger.getLogger(SendSuppliesPhase.class.getName());

    private final String factionId;
    private final String sourceMarketId;
    private final int combatFP;
    private final float travelDays;
    private final String subfactionName;

    private transient CampaignFleetAPI fleet;
    private String fleetId;

    private boolean done = false;
    private boolean succeeded = false;
    private boolean fleetSpawned = false;

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

        if (!fleetSpawned) {
            if (!isSectorAvailable()) {
                log.info("SendSuppliesPhase: no sector (sim mode); auto-completing as success.");
                succeeded = true;
                done = true;
                return;
            }
            spawnFleet();
            return;
        }

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
                log.warning("SendSuppliesPhase: fleet " + fleetId + " not found; treating as destroyed.");
                succeeded = false;
                done = true;
                return;
            }
        }

        if (fleet != null && fleet.isEmpty()) {
            succeeded = false;
            done = true;
        }
    }

    private void spawnFleet() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketId);

        if (source == null || source.getPrimaryEntity() == null) {
            log.warning("SendSuppliesPhase: source market missing; aborting.");
            succeeded = false;
            done = true;
            return;
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
            log.warning("SendSuppliesPhase: failed to create fleet; aborting.");
            succeeded = false;
            done = true;
            return;
        }

        fleet = created;
        fleet.setName(subfactionName + " Supply Convoy");
        fleet.setNoAutoDespawn(true);

        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
        fleet.getMemoryWithoutUpdate().set("$intrigueSupplyConvoy", true);

        SectorEntityToken sourceEntity = source.getPrimaryEntity();
        sourceEntity.getContainingLocation().addEntity(fleet);
        fleet.setLocation(sourceEntity.getLocation().x, sourceEntity.getLocation().y);

        fleet.addEventListener(this);

        // Travel out, patrol briefly, then return and despawn
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, sourceEntity, travelDays,
                "Delivering supplies for " + subfactionName);
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, sourceEntity, 120f,
                "Returning home");

        fleetId = fleet.getId();
        fleetSpawned = true;

        log.info("SendSuppliesPhase: spawned convoy " + fleetId + " (" + combatFP + " FP) at "
                + source.getName() + " for " + travelDays + " days");
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
        if (!fleetSpawned) return "Preparing supply convoy";
        if (done) return succeeded ? "Supplies delivered" : "Convoy destroyed";
        return "Convoy en route";
    }

    public boolean didSucceed() {
        return succeeded;
    }

    // ── FleetEventListener ──────────────────────────────────────────────

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (fleet == null || this.fleet == null) return;
        if (!fleet.getId().equals(this.fleetId)) return;

        if (battle.wasFleetDefeated(fleet, primaryWinner)) {
            succeeded = false;
            done = true;
            log.info("SendSuppliesPhase: convoy " + fleetId + " was destroyed.");
        }
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet == null || this.fleet == null) return;
        if (!fleet.getId().equals(this.fleetId)) return;

        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            succeeded = false;
        } else {
            succeeded = true;
        }
        done = true;
        log.info("SendSuppliesPhase: convoy " + fleetId + " despawned. Reason: " + reason
                + ", succeeded: " + succeeded);
    }
}

