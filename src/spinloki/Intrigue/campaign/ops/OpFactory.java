package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;

/**
 * Factory for creating operations. Decouples OpEvaluator from concrete op types,
 * allowing sim-mode implementations that don't require game-bound phases.
 */
public interface OpFactory {

    /**
     * Create a raid operation.
     *
     * @param opId           unique operation ID
     * @param initiator      the attacking person
     * @param target         the defending person
     * @return an IntrigueOp, or null if the op cannot be created
     */
    IntrigueOp createRaidOp(String opId, IntriguePerson initiator, IntriguePerson target);
}

