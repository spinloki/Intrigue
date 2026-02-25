package spinloki.Intrigue.campaign.ops;

import java.io.Serializable;

/**
 * The final result of an IntrigueOp once it reaches the RESOLVED stage.
 */
public enum OpOutcome implements Serializable {
    /** Op completed and achieved its goal. */
    SUCCESS,

    /** Op completed but failed to achieve its goal (e.g. fleet destroyed). */
    FAILURE,

    /** Op was cancelled before completion (person died, market lost, etc.). */
    ABORTED,

    /** Op is still in progress — not yet resolved. */
    PENDING
}

