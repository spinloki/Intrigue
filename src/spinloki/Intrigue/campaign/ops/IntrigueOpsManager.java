package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.IntrigueIds;

import java.io.Serializable;
import java.util.*;

/**
 * Singleton that owns and advances all active {@link IntrigueOp} instances.
 *
 * Stored in sector persistent data. Registered as an EveryFrameScript so
 * ops are advanced each frame automatically.
 */
public class IntrigueOpsManager implements EveryFrameScript, Serializable {

    private final List<IntrigueOp> activeOps = new ArrayList<>();
    private int nextOpSeq = 1;

    // ── Singleton access ────────────────────────────────────────────────

    public static IntrigueOpsManager get() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_OPS_MANAGER_KEY);

        if (existing instanceof IntrigueOpsManager) {
            return (IntrigueOpsManager) existing;
        }

        IntrigueOpsManager created = new IntrigueOpsManager();
        data.put(IntrigueIds.PERSIST_OPS_MANAGER_KEY, created);
        return created;
    }

    // ── Op management ───────────────────────────────────────────────────

    /** Generate a unique op ID. */
    public String nextOpId(String prefix) {
        return prefix + "_" + (nextOpSeq++);
    }

    /**
     * Register and start an op. The op transitions from PROPOSED → ACTIVE.
     */
    public void startOp(IntrigueOp op) {
        activeOps.add(op);
        op.start();
    }

    /** All currently active (non-resolved) ops. */
    public List<IntrigueOp> getActiveOps() {
        return Collections.unmodifiableList(activeOps);
    }

    /** Get all active ops where the given person is the initiator. */
    public List<IntrigueOp> getOpsInitiatedBy(String personId) {
        List<IntrigueOp> result = new ArrayList<>();
        for (IntrigueOp op : activeOps) {
            if (personId.equals(op.getInitiatorId())) result.add(op);
        }
        return result;
    }

    /** Get all active ops where the given person is the target. */
    public List<IntrigueOp> getOpsTargeting(String personId) {
        List<IntrigueOp> result = new ArrayList<>();
        for (IntrigueOp op : activeOps) {
            if (personId.equals(op.getTargetId())) result.add(op);
        }
        return result;
    }

    /** Get all active ops involving a person (as initiator, target, or participant). */
    public List<IntrigueOp> getOpsInvolving(String personId) {
        List<IntrigueOp> result = new ArrayList<>();
        for (IntrigueOp op : activeOps) {
            if (personId.equals(op.getInitiatorId()) ||
                personId.equals(op.getTargetId()) ||
                op.getParticipantIds().contains(personId)) {
                result.add(op);
            }
        }
        return result;
    }

    /** Check if a person already has an active op (as initiator). */
    public boolean hasActiveOp(String personId) {
        return !getOpsInitiatedBy(personId).isEmpty();
    }

    // ── EveryFrameScript ────────────────────────────────────────────────

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    @Override
    public void advance(float amount) {
        float days = Misc.getDays(amount);

        // Advance all ops; collect resolved ones for cleanup.
        Iterator<IntrigueOp> it = activeOps.iterator();
        while (it.hasNext()) {
            IntrigueOp op = it.next();
            op.advance(days);
            if (op.isResolved()) {
                it.remove();
            }
        }
    }
}

