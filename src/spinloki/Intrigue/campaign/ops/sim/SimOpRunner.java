package spinloki.Intrigue.campaign.ops.sim;


import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.spi.IntrigueOpRunner;

import java.util.*;

/**
 * Sim-side op runner. Manages ops without EveryFrameScript or Global.*
 */
public class SimOpRunner implements IntrigueOpRunner {

    private final List<IntrigueOp> activeOps = new ArrayList<>();
    private int nextOpSeq = 1;

    @Override
    public String nextOpId(String prefix) {
        return prefix + "_sim_" + (nextOpSeq++);
    }

    @Override
    public void startOp(IntrigueOp op) {
        activeOps.add(op);
        op.start();
    }

    @Override
    public boolean hasActiveOp(String personId) {
        for (IntrigueOp op : activeOps) {
            if (personId.equals(op.getInitiatorId())) return true;
        }
        return false;
    }

    @Override
    public int getActiveOpCount(String personId) {
        int count = 0;
        for (IntrigueOp op : activeOps) {
            if (personId.equals(op.getInitiatorId())) count++;
        }
        return count;
    }

    @Override
    public List<IntrigueOp> getOpsInitiatedBy(String personId) {
        List<IntrigueOp> result = new ArrayList<>();
        for (IntrigueOp op : activeOps) {
            if (personId.equals(op.getInitiatorId())) result.add(op);
        }
        return result;
    }

    /** Advance all active ops by the given days and remove resolved ones. */
    public void advance(float days) {
        Iterator<IntrigueOp> it = activeOps.iterator();
        while (it.hasNext()) {
            IntrigueOp op = it.next();
            op.advance(days);
            if (op.isResolved()) it.remove();
        }
    }

    public List<IntrigueOp> getActiveOps() {
        return Collections.unmodifiableList(activeOps);
    }
}

