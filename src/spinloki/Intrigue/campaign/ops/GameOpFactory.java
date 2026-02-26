package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;

/**
 * Game-side OpFactory that creates real RaidOps with TravelAndFightPhase.
 */
public class GameOpFactory implements OpFactory {

    @Override
    public IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction) {
        return new RaidOp(opId, attackerSubfaction, targetSubfaction);
    }
}