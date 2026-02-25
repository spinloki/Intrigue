package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for all Intrigue operations.
 *
 * An operation is a multi-phase action initiated by one IntriguePerson,
 * typically targeting another person or a market. The lifecycle is:
 *
 *   PROPOSED  →  ACTIVE  →  RESOLVED
 *
 * Subclasses populate {@link #phases} in their constructor and implement
 * {@link #applyOutcome()} to modify power/relationships/traits when done.
 *
 * The op is advanced each frame by {@link IntrigueOpsManager}.
 */
public abstract class IntrigueOp implements Serializable {

    public enum Stage {
        PROPOSED,   // evaluated but not yet started
        ACTIVE,     // phases are being advanced
        RESOLVED    // all phases done (or aborted); outcome applied
    }

    private final String opId;
    private final String initiatorId;
    private final String targetId;       // person ID or market ID depending on op type
    private final List<String> participantIds = new ArrayList<>(); // future: coalition members

    private Stage stage = Stage.PROPOSED;
    private OpOutcome outcome = OpOutcome.PENDING;

    protected final List<OpPhase> phases = new ArrayList<>();
    private int currentPhaseIndex = 0;

    protected IntrigueOp(String opId, String initiatorId, String targetId) {
        this.opId = opId;
        this.initiatorId = initiatorId;
        this.targetId = targetId;
    }

    // ── Identification ──────────────────────────────────────────────────

    public String getOpId() { return opId; }
    public String getInitiatorId() { return initiatorId; }
    public String getTargetId() { return targetId; }
    public List<String> getParticipantIds() { return Collections.unmodifiableList(participantIds); }
    protected void addParticipant(String personId) { participantIds.add(personId); }

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

    // ── Helpers for subclasses ──────────────────────────────────────────

    protected IntriguePerson getInitiator() {
        return IntrigueServices.people().getById(initiatorId);
    }

    protected IntriguePerson getTargetPerson() {
        return IntrigueServices.people().getById(targetId);
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
     * Default checks that initiator and target still exist.
     */
    protected boolean shouldAbort() {
        return getInitiator() == null;
        // Note: target may be null for some op types (market-targeted ops) — subclasses override.
    }

    /** Human-readable op type name for logs and UI. */
    public abstract String getOpTypeName();

    // ── Internal ────────────────────────────────────────────────────────

    private void resolve(OpOutcome result) {
        if (stage == Stage.RESOLVED) return;
        this.outcome = result;
        this.stage = Stage.RESOLVED;
        applyOutcome();
    }
}

