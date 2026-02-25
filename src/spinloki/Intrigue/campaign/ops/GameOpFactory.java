package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;

/**
 * Game-side OpFactory that creates real RaidOps with TravelAndFightPhase.
 */
public class GameOpFactory implements OpFactory {

    @Override
    public IntrigueOp createRaidOp(String opId, IntriguePerson initiator, IntriguePerson target) {
        return new RaidOp(opId, initiator.getPersonId(), target.getPersonId(),
                          target.getHomeMarketId());
    }
}

