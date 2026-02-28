package spinloki.Intrigue.campaign.spi;


import spinloki.Intrigue.campaign.ops.IntrigueOp;

import java.util.List;

/**
 * Abstraction over op lifecycle management.
 *
 * In-game: implemented by IntrigueOpsManager.
 * In-sim: implemented by a lightweight stub.
 */
public interface IntrigueOpRunner {

    /** Generate a unique op ID. */
    String nextOpId(String prefix);

    /** Register and start an op (PROPOSED → ACTIVE). */
    void startOp(IntrigueOp op);

    /** Check if a person has an active op as initiator. */
    boolean hasActiveOp(String personId);

    /** Count active ops where the given person is the initiator. */
    int getActiveOpCount(String personId);

    /** Get all active ops where the given person is the initiator. */
    List<IntrigueOp> getOpsInitiatedBy(String personId);
}

