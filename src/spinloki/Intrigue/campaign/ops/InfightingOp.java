package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * Consequence op: infighting erupts in a territory where cohesion has dropped
 * below the danger threshold. Subfaction fleets spawn and fight each other.
 * Result: legitimacy loss.
 */
public class InfightingOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(InfightingOp.class.getName());
    private static final int LEGITIMACY_LOSS = 8;

    private final String subfactionId;

    public InfightingOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);
        // TODO: game-side phase that spawns fighting fleets in the territory
        // For now, resolves instantly
        phases.add(new TimedPhase("Infighting", 3f));
    }

    @Override public String getOpTypeName() { return "Infighting"; }
    @Override protected void onStarted() {
        log.info("Infighting erupted for " + subfactionId + " in territory " + getTerritoryId());
    }

    @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null) {
            sf.setLegitimacy(sf.getLegitimacy() - LEGITIMACY_LOSS);
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            log.info("Infighting resolved: " + subfactionId + " legitimacy -" + LEGITIMACY_LOSS);
        }
    }

    /** Simple phase that completes after a set number of days. */
    static class TimedPhase implements OpPhase, java.io.Serializable {
        private final String label;
        private final float duration;
        private float elapsed = 0f;

        TimedPhase(String label, float duration) {
            this.label = label;
            this.duration = duration;
        }

        @Override public void advance(float days) { elapsed += days; }
        @Override public boolean isDone() { return elapsed >= duration; }
        @Override public String getStatus() { return isDone() ? label + " subsided" : label + " ongoing"; }
    }
}


