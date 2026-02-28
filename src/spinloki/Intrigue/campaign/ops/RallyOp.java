package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * Rally op: the subfaction consolidates its home base, shoring up internal
 * cohesion. A fleet spawns and parades around the home system. If it
 * survives, home cohesion increases; if it's destroyed, the rally fails
 * but doesn't cost anything extra.
 *
 * Flavored differently per faction:
 * - Hegemony: military parade / loyalty review
 * - Tri-Tachyon: corporate restructuring
 * - Church: pilgrimage / revival
 * - Pirates/Pathers: strongman display / purge
 */
public class RallyOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(RallyOp.class.getName());
    private static final int COHESION_GAIN = 10;
    private static final int SABOTAGE_COHESION_GAIN_REDUCTION = 5;
    private static final float RALLY_DAYS = 15f;

    private final RallyPhase rallyPhase;
    private boolean sabotaged = false;

    public RallyOp(String opId, IntrigueSubfaction subfaction) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);

        // Fleet strength scales with home cohesion: 15 FP at 0, 55 FP at 100
        int combatFP = 15 + (int) (subfaction.getHomeCohesion() * 0.4f);

        this.rallyPhase = new RallyPhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                combatFP,
                RALLY_DAYS,
                subfaction.getName());
        phases.add(rallyPhase);
    }

    @Override public String getOpTypeName() { return "Rally"; }

    // ── Mischief targeting ────────────────────────────────────────────

    @Override
    public boolean canBeTargetedByMischief() {
        return !isResolved();
    }

    @Override
    public String describeMischiefEffect() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        String name = sf != null ? sf.getName() : getInitiatorSubfactionId();
        return "Disrupting " + name + "'s rally";
    }

    @Override
    public void applyMischiefSabotage() {
        sabotaged = true;
        log.info("RallyOp " + getOpId() + " sabotaged — cohesion gain will be reduced");
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override protected void onStarted() {
        log.info("Rally started for " + getInitiatorSubfactionId());
    }

    @Override protected OpOutcome determineOutcome() {
        return rallyPhase.didSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null) {
            if (getOutcome() == OpOutcome.SUCCESS) {
                int gain = sabotaged
                        ? Math.max(0, COHESION_GAIN - SABOTAGE_COHESION_GAIN_REDUCTION)
                        : COHESION_GAIN;
                sf.setHomeCohesion(sf.getHomeCohesion() + gain);
                log.info("Rally succeeded: " + getInitiatorSubfactionId()
                        + " home cohesion +" + gain
                        + (sabotaged ? " (sabotaged, reduced from +" + COHESION_GAIN + ")" : ""));
            } else {
                log.info("Rally failed: " + getInitiatorSubfactionId()
                        + " rally fleet destroyed (no effect)");
            }
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }
    }
}

