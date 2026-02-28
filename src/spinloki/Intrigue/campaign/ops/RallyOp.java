package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * Rally op: the subfaction consolidates its home base, shoring up internal
 * cohesion. Success boosts home cohesion; failure wastes time but doesn't hurt.
 *
 * Flavored differently per faction:
 * - Hegemony: military parade / loyalty review
 * - Tri-Tachyon: corporate restructuring
 * - Church: pilgrimage / revival
 * - Pirates/Pathers: strongman display / purge
 */
public class RallyOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(RallyOp.class.getName());
    private static final int COHESION_GAIN = 10;

    public RallyOp(String opId, IntrigueSubfaction subfaction) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        // TODO: game-side phase — could be a timed event at the home market
        phases.add(new TimedPhase("Rally", 5f));
    }

    @Override public String getOpTypeName() { return "Rally"; }

    @Override protected void onStarted() {
        log.info("Rally started for " + getInitiatorSubfactionId());
    }

    @Override protected OpOutcome determineOutcome() {
        // Game-side: always succeeds for now (TODO: tie to actual game events)
        return OpOutcome.SUCCESS;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null) {
            if (getOutcome() == OpOutcome.SUCCESS) {
                sf.setHomeCohesion(sf.getHomeCohesion() + COHESION_GAIN);
                log.info("Rally succeeded: " + getInitiatorSubfactionId()
                        + " home cohesion +" + COHESION_GAIN);
            } else {
                log.info("Rally failed: " + getInitiatorSubfactionId() + " (no effect)");
            }
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }
    }
}

