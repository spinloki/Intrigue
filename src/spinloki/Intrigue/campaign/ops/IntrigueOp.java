package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for all Intrigue operations.
 *
 * An operation is a multi-phase action initiated by one subfaction
 * against another, with the subfaction's leader serving as the
 * on-the-ground executor. The lifecycle is:
 *
 *   PROPOSED  →  ACTIVE  →  RESOLVED
 *
 * Subclasses populate {@link #phases} in their constructor and implement
 * {@link #applyOutcome()} to modify power/relationships when done.
 *
 * The op is advanced each frame by {@link IntrigueOpsManager}.
 */
public abstract class IntrigueOp implements Serializable {

    public enum Stage {
        PROPOSED,
        ACTIVE,
        RESOLVED
    }

    private final String opId;
    private final String initiatorId;            // leader person ID (for checkout/abort)
    private final String targetId;               // target leader person ID
    private final String initiatorSubfactionId;   // attacking subfaction
    private final String targetSubfactionId;      // defending subfaction
    private final List<String> participantIds = new ArrayList<>();

    /** Optional: the territory this op takes place in (null for core-world ops). */
    private String territoryId;

    /**
     * When true, this op is "free" — it doesn't set cooldown on the initiating
     * subfaction and doesn't impose failure costs. Used for vulnerability raids
     * triggered when a target's legitimacy hits 0.
     */
    private boolean noCost = false;

    /**
     * Accumulated success chance penalty from active mischief ops targeting this op.
     * Applied as a negative modifier when this op resolves (e.g. 0.15 = 15% penalty).
     */
    private float mischiefPenalty = 0f;

    /** True if mischief sabotage flipped this op's outcome from SUCCESS to FAILURE. */
    private boolean sabotagedByMischief = false;

    private Stage stage = Stage.PROPOSED;
    private OpOutcome outcome = OpOutcome.PENDING;

    protected final List<OpPhase> phases = new ArrayList<>();
    private int currentPhaseIndex = 0;

    protected IntrigueOp(String opId, String initiatorId, String targetId,
                         String initiatorSubfactionId, String targetSubfactionId) {
        this.opId = opId;
        this.initiatorId = initiatorId;
        this.targetId = targetId;
        this.initiatorSubfactionId = initiatorSubfactionId;
        this.targetSubfactionId = targetSubfactionId;
    }

    // ── Identification ──────────────────────────────────────────────────

    public String getOpId() { return opId; }
    public String getInitiatorId() { return initiatorId; }
    public String getTargetId() { return targetId; }
    public String getInitiatorSubfactionId() { return initiatorSubfactionId; }
    public String getTargetSubfactionId() { return targetSubfactionId; }
    public List<String> getParticipantIds() { return Collections.unmodifiableList(participantIds); }
    protected void addParticipant(String personId) { participantIds.add(personId); }

    /** Territory this op is scoped to, or null for core-world ops. */
    public String getTerritoryId() { return territoryId; }
    public void setTerritoryId(String territoryId) { this.territoryId = territoryId; }

    /** Whether this op is free (no cooldown, no failure costs). */
    public boolean isNoCost() { return noCost; }
    public void setNoCost(boolean noCost) { this.noCost = noCost; }

    /**
     * Success chance penalty applied by active mischief ops targeting this op.
     * 0.0 = no penalty, 0.15 = 15% penalty, etc.
     */
    public float getMischiefPenalty() { return mischiefPenalty; }

    /** Add to the accumulated mischief penalty on this op. */
    public void addMischiefPenalty(float penalty) { this.mischiefPenalty += penalty; }

    /** True if mischief sabotage flipped this op's outcome from SUCCESS to FAILURE. */
    public boolean wasSabotagedByMischief() { return sabotagedByMischief; }

    // ── Convenience lookups ─────────────────────────────────────────────

    protected IntriguePerson getInitiator() {
        return IntrigueServices.people().getById(initiatorId);
    }

    protected IntriguePerson getTargetPerson() {
        return IntrigueServices.people().getById(targetId);
    }

    protected IntrigueSubfaction getInitiatorSubfaction() {
        return IntrigueServices.subfactions().getById(initiatorSubfactionId);
    }

    protected IntrigueSubfaction getTargetSubfaction() {
        return IntrigueServices.subfactions().getById(targetSubfactionId);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    public Stage getStage() { return stage; }
    public OpOutcome getOutcome() { return outcome; }

    /** Start the op: transition from PROPOSED → ACTIVE. Called by the ops manager. */
    public void start() {
        if (stage != Stage.PROPOSED) return;
        stage = Stage.ACTIVE;
        onStarted();
    }

    /**
     * Advance the currently-active phase. When all phases complete, resolve.
     * Called each frame by the ops manager.
     */
    public void advance(float days) {
        if (stage != Stage.ACTIVE) return;

        if (shouldAbort()) {
            resolve(OpOutcome.ABORTED);
            return;
        }

        if (currentPhaseIndex >= phases.size()) {
            // All phases done — subclass decides success/failure
            resolve(determineOutcome());
            return;
        }

        OpPhase phase = phases.get(currentPhaseIndex);
        phase.advance(days);

        if (phase.isDone()) {
            currentPhaseIndex++;
            // If that was the last one, resolve next frame (or now)
            if (currentPhaseIndex >= phases.size()) {
                resolve(determineOutcome());
            }
        }
    }

    public boolean isResolved() {
        return stage == Stage.RESOLVED;
    }

    // ── Current state ───────────────────────────────────────────────────

    public OpPhase getCurrentPhase() {
        if (currentPhaseIndex < phases.size()) return phases.get(currentPhaseIndex);
        return null;
    }

    public int getCurrentPhaseIndex() { return currentPhaseIndex; }
    public int getPhaseCount() { return phases.size(); }

    public String getStatusText() {
        OpPhase phase = getCurrentPhase();
        if (stage == Stage.PROPOSED) return "Proposed";
        if (stage == Stage.RESOLVED) return "Resolved: " + outcome;
        return phase != null ? phase.getStatus() : "Active";
    }

    // ── Subclass hooks ──────────────────────────────────────────────────

    /** Called when the op transitions to ACTIVE. Good place to checkout people, etc. */
    protected abstract void onStarted();

    /** Called when the op is fully resolved. Apply power/rel/trait changes here. */
    protected abstract void applyOutcome();

    /**
     * Determine the outcome after all phases complete.
     * Subclasses override to inspect phase results.
     */
    protected abstract OpOutcome determineOutcome();

    /**
     * Check whether the op should be aborted (e.g. initiator no longer exists).
     * Default checks that the initiator still exists. Subclasses may add additional checks.
     */
    protected boolean shouldAbort() {
        return getInitiator() == null;
    }

    /** Human-readable op type name for logs and UI. */
    public abstract String getOpTypeName();

    // ── Internal ────────────────────────────────────────────────────────

    private void resolve(OpOutcome result) {
        if (stage == Stage.RESOLVED) return;

        // Mischief penalty: if the op would succeed and has accumulated mischief
        // sabotage, there is a chance the success is flipped to failure.
        // The penalty is a probability (e.g. 0.15 = 15% chance of sabotage).
        if (result == OpOutcome.SUCCESS && mischiefPenalty > 0f) {
            float roll = (float) Math.random();
            if (roll < mischiefPenalty) {
                result = OpOutcome.FAILURE;
                sabotagedByMischief = true;
            }
        }

        this.outcome = result;
        this.stage = Stage.RESOLVED;
        applyOutcome();
    }
}