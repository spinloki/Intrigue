package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: a subfaction sends a supply convoy from its home market to
 * one of its established territories. If the convoy arrives safely,
 * territory cohesion increases. If it is intercepted and destroyed,
 * territory cohesion drops sharply.
 */
public class SendSuppliesOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(SendSuppliesOp.class.getName());

    private static final int COHESION_GAIN = 8;
    private static final int COHESION_LOSS = 10;
    private static final float TRAVEL_DAYS = 25f;

    private final String subfactionId;
    private final SendSuppliesPhase supplyPhase;

    public SendSuppliesOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
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

        int combatFP = 15 + (int) (subfaction.getHomeCohesion() * 0.3f);

        this.supplyPhase = new SendSuppliesPhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                combatFP,
                TRAVEL_DAYS,
                subfaction.getName());
        phases.add(supplyPhase);
    }

    @Override
    public String getOpTypeName() {
        return "Send Supplies";
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        log.info("SendSuppliesOp started: " + subfactionId
                + " (leader " + (leader != null ? leader.getPersonId() : "?") + ")"
                + " resupplying territory " + getTerritoryId());
    }

    @Override
    protected boolean shouldAbort() {
        if (getInitiator() == null) return true;

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            IntrigueTerritory territory = territories.getById(getTerritoryId());
            if (territory == null) return true;
            if (territory.getPresence(subfactionId) != IntrigueTerritory.Presence.ESTABLISHED) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        return supplyPhase.didSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction subfaction = getInitiatorSubfaction();

        if (subfaction != null) {
            subfaction.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;
        String territoryName = territory != null ? territory.getName() : getTerritoryId();

        if (result == OpOutcome.SUCCESS) {
            if (territory != null) {
                territory.setCohesion(subfactionId,
                        territory.getCohesion(subfactionId) + COHESION_GAIN);
            }
            log.info("SendSuppliesOp resolved SUCCESS: " + subfactionId
                    + " territory cohesion +" + COHESION_GAIN
                    + " (" + territoryName + ")");
        } else {
            if (territory != null) {
                territory.setCohesion(subfactionId,
                        territory.getCohesion(subfactionId) - COHESION_LOSS);
            }
            log.info("SendSuppliesOp resolved FAILURE: " + subfactionId
                    + " convoy destroyed, territory cohesion -" + COHESION_LOSS
                    + " (" + territoryName + ")");
        }

        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }
}

