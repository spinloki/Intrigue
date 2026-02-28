package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.ops.OpOutcome;

/**
 * Determines the outcome of a simulated operation. Decouples outcome logic
 * from the sim op classes so that success/failure probabilities can be
 * tuned externally via {@link SimConfig}.
 *
 * Each method receives whatever context it needs (subfaction stats, config)
 * and returns an {@link OpOutcome}.
 */
public interface OpOutcomeResolver {

    /** Resolve a raid between attacker and defender. */
    OpOutcome resolveRaid(int attackerCohesion, int defenderCohesion, boolean attackerMerciless);

    /** Resolve an establish-base op for a homeless subfaction. */
    OpOutcome resolveEstablishBase(IntrigueSubfaction subfaction);

    /** Resolve a scout-territory op. */
    OpOutcome resolveScoutTerritory(IntrigueSubfaction subfaction, String territoryId);

    /** Resolve an establish-territory-base op. */
    OpOutcome resolveEstablishTerritoryBase(IntrigueSubfaction subfaction, String territoryId);

    /** Resolve a patrol op. */
    OpOutcome resolvePatrol(IntrigueSubfaction subfaction, String territoryId);
}

