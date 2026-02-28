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

    @Override
    public IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction,
                                   IntrigueSubfaction targetSubfaction, String targetMarketId) {
        return new RaidOp(opId, attackerSubfaction, targetSubfaction, targetMarketId);
    }

    @Override
    public IntrigueOp createEstablishBaseOp(String opId, IntrigueSubfaction subfaction) {
        return new EstablishBaseOp(opId, subfaction);
    }

    @Override
    public IntrigueOp createScoutTerritoryOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new ScoutTerritoryOp(opId, subfaction, territoryId);
    }

    @Override
    public IntrigueOp createEstablishTerritoryBaseOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new EstablishTerritoryBaseOp(opId, subfaction, territoryId);
    }

    @Override
    public IntrigueOp createPatrolOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new PatrolOp(opId, subfaction, territoryId);
    }

    @Override
    public IntrigueOp createSendSuppliesOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SendSuppliesOp(opId, subfaction, territoryId);
    }

    @Override
    public IntrigueOp createRallyOp(String opId, IntrigueSubfaction subfaction) {
        return new RallyOp(opId, subfaction);
    }

    @Override
    public IntrigueOp createInfightingOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new InfightingOp(opId, subfaction, territoryId);
    }

    @Override
    public IntrigueOp createExpulsionOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new ExpulsionOp(opId, subfaction, territoryId);
    }

    @Override
    public IntrigueOp createCivilWarOp(String opId, IntrigueSubfaction subfaction) {
        return new CivilWarOp(opId, subfaction);
    }

    @Override
    public IntrigueOp createMischiefOp(String opId, IntrigueSubfaction initiator,
                                       IntrigueSubfaction victim, String territoryId,
                                       IntrigueOp targetOp) {
        return new MischiefOp(opId, initiator, victim, territoryId, targetOp);
    }
}