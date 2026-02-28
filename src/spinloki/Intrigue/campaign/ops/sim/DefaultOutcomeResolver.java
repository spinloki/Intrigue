package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.ops.OpOutcome;

import java.util.Random;

/**
 * Default outcome resolver that uses {@link SimConfig} probabilities and
 * a sigmoid model for combat. All the knobs live in SimConfig; this class
 * just reads them.
 */
public class DefaultOutcomeResolver implements OpOutcomeResolver {

    private final Random rng;
    private final SimConfig config;

    public DefaultOutcomeResolver(Random rng, SimConfig config) {
        this.rng = rng;
        this.config = config;
    }

    @Override
    public OpOutcome resolveRaid(int attackerCohesion, int defenderCohesion, boolean attackerMerciless) {
        int attackerFP = config.baseFP + (int) (attackerCohesion * config.fpPerPower);
        int defenderFP = config.defenderBaseFP + (int) (defenderCohesion * config.defenderFpPerPower);

        int fpGap = attackerFP - defenderFP;
        if (fpGap > 0) {
            defenderFP += (int) (fpGap * config.underdogFpBonus);
        }

        double diff = attackerFP - defenderFP;
        double prob = 1.0 / (1.0 + Math.exp(-config.combatSteepness * diff));
        return rng.nextDouble() < prob ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    public OpOutcome resolveEstablishBase(IntrigueSubfaction subfaction) {
        return rng.nextFloat() < config.establishBaseSuccessProb ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    public OpOutcome resolveScoutTerritory(IntrigueSubfaction subfaction, String territoryId) {
        return rng.nextFloat() < config.scoutTerritorySuccessProb ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    public OpOutcome resolveEstablishTerritoryBase(IntrigueSubfaction subfaction, String territoryId) {
        return rng.nextFloat() < config.establishTerritoryBaseSuccessProb ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    public OpOutcome resolvePatrol(IntrigueSubfaction subfaction, String territoryId) {
        return rng.nextFloat() < config.patrolSuccessProb ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }
}

