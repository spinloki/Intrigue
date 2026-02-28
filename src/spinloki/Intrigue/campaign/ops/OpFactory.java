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

    /**
     * Create an operation to establish a base for a homeless subfaction.
     *
     * @param opId       unique operation ID
     * @param subfaction the subfaction establishing a base
     * @return an IntrigueOp, or null if the op cannot be created
     */
    IntrigueOp createEstablishBaseOp(String opId, IntrigueSubfaction subfaction);

    /**
     * Create a scouting operation for a territory.
     * Moves the subfaction's presence from NONE to SCOUTING.
     *
     * @param opId        unique operation ID
     * @param subfaction  the subfaction sending scouts
     * @param territoryId the target territory
     * @return an IntrigueOp, or null if the op cannot be created
     */
    IntrigueOp createScoutTerritoryOp(String opId, IntrigueSubfaction subfaction, String territoryId);

    /**
     * Create an operation to establish a base inside a scouted territory.
     * Moves the subfaction's presence from SCOUTING to ESTABLISHED.
     *
     * @param opId        unique operation ID
     * @param subfaction  the subfaction establishing a territorial base
     * @param territoryId the target territory (must already be SCOUTING)
     * @return an IntrigueOp, or null if the op cannot be created
     */
    IntrigueOp createEstablishTerritoryBaseOp(String opId, IntrigueSubfaction subfaction, String territoryId);

    /**
     * Create a patrol operation in an established territory.
     * Spawns a patrol fleet; success grants legitimacy, destruction costs it.
     *
     * @param opId        unique operation ID
     * @param subfaction  the subfaction sending the patrol
     * @param territoryId the territory to patrol (must be ESTABLISHED)
     * @return an IntrigueOp, or null if the op cannot be created
     */
    IntrigueOp createPatrolOp(String opId, IntrigueSubfaction subfaction, String territoryId);
}