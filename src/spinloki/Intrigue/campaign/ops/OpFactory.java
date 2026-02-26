package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;

/**
 * Factory for creating operations. Decouples OpEvaluator from concrete op types,
 * allowing sim-mode implementations that don't require game-bound phases.
 */
public interface OpFactory {

    /**
     * Create a raid operation between two subfactions.
     *
     * @param opId              unique operation ID
     * @param attackerSubfaction the attacking subfaction
     * @param targetSubfaction   the defending subfaction
     * @return an IntrigueOp, or null if the op cannot be created
     */
    IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction);
}