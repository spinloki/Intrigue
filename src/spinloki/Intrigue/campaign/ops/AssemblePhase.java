package spinloki.Intrigue.campaign.ops;

/**
 * Phase 1 of a RaidOp: the initiator musters forces.
 *
 * This is a simple timed delay representing the time it takes to assemble
 * a raiding fleet. Duration scales inversely with cohesion (more cohesive
 * subfactions can mobilise faster).
 */
public class AssemblePhase implements OpPhase {

    private final float durationDays;
    private float elapsed = 0f;

    /**
     * @param initiatorCohesion the initiator's cohesion (0–100); higher = shorter delay
     */
    public AssemblePhase(int initiatorCohesion) {
        // 3-7 days: cohesive subfactions assemble faster
        float t = 1f - (initiatorCohesion / 100f); // 0 at 100 cohesion, 1 at 0 cohesion
        this.durationDays = 3f + t * 4f;
    }

    @Override
    public void advance(float days) {
        elapsed += days;
    }

    @Override
    public boolean isDone() {
        return elapsed >= durationDays;
    }

    @Override
    public String getStatus() {
        float remaining = Math.max(0, durationDays - elapsed);
        return String.format("Assembling forces (%.1f days remaining)", remaining);
    }
}

