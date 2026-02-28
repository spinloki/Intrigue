package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * Consequence op: infighting erupts in a territory where cohesion has dropped
 * below the danger threshold.
 *
 * <p>Two fleets from the same subfaction travel to a point of interest in the
 * territory to "settle their differences." When they arrive, they become
 * hostile to each other and fight. The outcome is always FAILURE - infighting
 * always hurts the subfaction, resulting in legitimacy loss.</p>
 */
public class InfightingOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(InfightingOp.class.getName());
    private static final int LEGITIMACY_LOSS = 8;

    private final String subfactionId;

    public InfightingOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);

        // Intel arrow: home market -> territory
        setIntelSourceMarketId(subfaction.getHomeMarketId());
        setIntelDestinationSystemId(IntrigueOpIntel.resolveSystemIdFromTerritory(territoryId));

        // Each dissident fleet gets roughly half the subfaction's strength
        int fleetFP = 15 + (int) (subfaction.getHomeCohesion() * 0.3f);

        phases.add(new InfightingPhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                territoryId,
                fleetFP,
                subfaction.getName()));
    }

    @Override public String getOpTypeName() { return "Infighting"; }

    @Override protected void onStarted() {
        log.info("Infighting erupted for " + subfactionId + " in territory " + getTerritoryId());
    }

    @Override protected OpOutcome determineOutcome() {
        // Infighting is always bad for the subfaction
        return OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null) {
            sf.setLegitimacy(sf.getLegitimacy() - LEGITIMACY_LOSS);
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            log.info("Infighting resolved: " + subfactionId + " legitimacy -" + LEGITIMACY_LOSS);
        }
    }
}
