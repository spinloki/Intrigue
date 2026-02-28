package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Consequence op: a subfaction is expelled from a territory after sustained
 * critically-low cohesion. Presence reverts to NONE and legitimacy takes
 * a massive hit.
 */
public class ExpulsionOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(ExpulsionOp.class.getName());
    private static final int LEGITIMACY_LOSS = 20;

    private final String subfactionId;

    public ExpulsionOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);
        // Resolves instantly - the expulsion is a fait accompli
        phases.add(new TimedPhase("Expelled", 1f));
    }

    @Override public String getOpTypeName() { return "Expulsion"; }
    @Override protected void onStarted() {
        log.info("Expulsion imminent for " + subfactionId + " from territory " + getTerritoryId());
    }

    @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        IntrigueTerritoryAccess territories = IntrigueServices.territories();

        if (territories != null) {
            IntrigueTerritory territory = territories.getById(getTerritoryId());
            if (territory != null) {
                territory.removeSubfaction(subfactionId);
                log.info("Expulsion: " + subfactionId + " removed from " + territory.getName());
            }
        }

        if (sf != null) {
            sf.setLegitimacy(sf.getLegitimacy() - LEGITIMACY_LOSS);
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            log.info("Expulsion resolved: " + subfactionId + " legitimacy -" + LEGITIMACY_LOSS);
        }
    }
}

