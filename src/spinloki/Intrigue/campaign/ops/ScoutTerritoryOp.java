package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: a subfaction scouts a territory to establish initial presence.
 *
 * This is a "self-op" scoped to a territory. On start, the territory presence
 * is set to SCOUTING. On success, it remains SCOUTING - the subfaction can
 * then launch an EstablishTerritoryBaseOp to advance to ESTABLISHED.
 *
 * On failure, presence reverts to NONE.
 */
public class ScoutTerritoryOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(ScoutTerritoryOp.class.getName());

    private static final int COHESION_COST = 5;
    private static final int LEGITIMACY_GAIN = 3;

    private final String subfactionId;
    private final ScoutTerritoryPhase scoutPhase;

    public ScoutTerritoryOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId,
              subfaction.getLeaderId(),
              null,
              subfaction.getSubfactionId(),
              null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);

        // Intel arrow: home market -> territory
        setIntelSourceMarketId(subfaction.getHomeMarketId());
        setIntelDestinationSystemId(IntrigueOpIntel.resolveSystemIdFromTerritory(territoryId));

        int combatFP = 10 + (int) (subfaction.getHomeCohesion() * 0.2f);

        this.scoutPhase = new ScoutTerritoryPhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                subfaction.getSubfactionId(),
                territoryId,
                combatFP,
                30f,      // 30 days to scout each system
                subfaction.getName());
        phases.add(scoutPhase);
    }

    @Override
    public String getOpTypeName() {
        return "Scout Territory";
    }

    @Override
    protected void onStarted() {
        // Immediately mark presence as SCOUTING
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            IntrigueTerritory territory = territories.getById(getTerritoryId());
            if (territory != null) {
                territory.setPresence(subfactionId, IntrigueTerritory.Presence.SCOUTING);
                log.info("ScoutTerritoryOp started: " + subfactionId
                        + " now SCOUTING " + territory.getName());
            }
        }
    }

    @Override
    protected boolean shouldAbort() {
        if (getInitiator() == null) return true;

        // Abort if territory no longer exists
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null && territories.getById(getTerritoryId()) == null) {
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        return scoutPhase.didSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction subfaction = getInitiatorSubfaction();

        if (subfaction != null) {
            subfaction.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        log.info("ScoutTerritoryOp resolved: " + getOpId() + " → " + result
                + " territory=" + getTerritoryId());

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;

        if (result == OpOutcome.SUCCESS) {
            // Presence stays at SCOUTING - next step is EstablishTerritoryBaseOp
            if (subfaction != null) {
                // Minor home cohesion cost (sent scouts out) but legitimacy gain (expanding influence)
                subfaction.setHomeCohesion(subfaction.getHomeCohesion() - COHESION_COST);
                subfaction.setLegitimacy(subfaction.getLegitimacy() + LEGITIMACY_GAIN);
            }
            log.info("  SUCCESS: " + subfactionId + " scouting complete in "
                    + (territory != null ? territory.getName() : getTerritoryId()));
        } else {
            // Revert presence to NONE
            if (territory != null) {
                territory.setPresence(subfactionId, IntrigueTerritory.Presence.NONE);
            }
            if (subfaction != null) {
                subfaction.setHomeCohesion(subfaction.getHomeCohesion() - COHESION_COST / 2);
            }
            log.info("  FAILURE: " + subfactionId + " scouting failed in "
                    + (territory != null ? territory.getName() : getTerritoryId()));
        }

        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }
}

