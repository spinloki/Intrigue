package spinloki.Intrigue.campaign.ops.sim;

/**
 * Configuration for sim-mode operations. All tunable parameters in one place.
 */
public class SimConfig {
    // ── Scoring ──
    public float minScoreThreshold = 10f;
    public float relWeight = 0.5f;
    public float powerDiffWeight = 0.3f;
    public float crossFactionBaseScore = 12f;

    // ── Trait modifiers (scoring) ──
    public float mercilessBonus = 15f;
    public float opportunistWeakBonus = 10f;
    public int opportunistWeakThreshold = 20;
    public float paranoidDistrust = 12f;
    public int paranoidDistrustThreshold = -10;
    public float honorBoundPenalty = 15f;
    public float honorBoundEnemyBonus = 20f;
    public int honorBoundEnemyThreshold = -50;
    public float charismaticPenalty = 10f;

    // ── Outcome ──
    public int basePowerShift = 8;
    public int mercilessBonusPower = 4;
    public int relDropOnRaid = -15;
    public int playerRelDropOnRaid = -2;

    // ── Combat resolution ──
    public int baseFP = 30;
    public float fpPerPower = 1.2f;
    public int defenderBaseFP = 30;
    public float defenderFpPerPower = 1.2f;
    public float combatSteepness = 0.03f;
    public float underdogFpBonus = 0.6f;

    // ── Anti-snowball ──
    public float meanReversionTarget = 50f;
    public float meanReversionRate = 0.05f;

    // ── Op success probabilities (sim only) ──
    /** Probability that a homeless subfaction successfully establishes a base. */
    public float establishBaseSuccessProb = 1.0f;
    /** Probability that a scout-territory op succeeds. */
    public float scoutTerritorySuccessProb = 0.9f;
    /** Probability that an establish-territory-base op succeeds. */
    public float establishTerritoryBaseSuccessProb = 0.85f;
    /** Probability that a patrol fleet survives (success). */
    public float patrolSuccessProb = 0.8f;
    /** Probability that a supply convoy reaches its territory. */
    public float sendSuppliesSuccessProb = 0.75f;
    /** Territory cohesion gained on successful supply run. */
    public int sendSuppliesCohesionGain = 15;
    /** Territory cohesion lost on failed supply run (convoy destroyed). */
    public int sendSuppliesCohesionLoss = 10;
    /** Probability that a rally succeeds. */
    public float rallySuccessProb = 0.85f;
    /** Home cohesion gained on successful rally. */
    public int rallyCohesionGain = 10;
    /** Home cohesion below which a rally op is considered. */
    public int rallyCohesionThreshold = 50;
    /** Legitimacy threshold above which Patrol and Raid are deprioritized. */
    public int highLegitimacyThreshold = 80;
    /** Territory cohesion decay per tick. */
    public int territoryCohesionDecayPerTick = 1;

    // ── Dysfunction thresholds ──
    /** Territory cohesion below which infighting can occur. */
    public int infightingCohesionThreshold = 30;
    /** Legitimacy lost when infighting occurs in a territory. */
    public int infightingLegitimacyLoss = 8;
    /** Territory cohesion below which the subfaction risks expulsion. */
    public int expulsionCohesionThreshold = 10;
    /** Consecutive ticks below expulsion threshold before the subfaction is expelled. */
    public int expulsionTicksRequired = 3;
    /** Legitimacy lost when a subfaction is expelled from a territory. */
    public int expulsionLegitimacyLoss = 20;
    /** Home cohesion below which civil war can trigger. */
    public int civilWarCohesionThreshold = 10;
    /** Consecutive ticks of low home cohesion before civil war fires. */
    public int civilWarTicksRequired = 3;

    // ── Pacing ──
    public int pacerNudgeMin = -1;
    public int pacerNudgeMax = 1;
    public float tickIntervalDays = 7f;

    // ── Regions ──
    public int prominencePowerThreshold = 75;

    // ── Concurrent ops ──
    /** Total cohesion (home + all established territories) required per extra concurrent op beyond the first. */
    public int extraOpCohesionCost = 50;
    /** Cohesion drained per tick per extra active op (beyond the first). Drains from a random territory or home. */
    public int concurrentOpDrainPerTick = 1;
    /** Hard cap on concurrent ops per subfaction. */
    public int maxConcurrentOps = 3;

    // ── Player intervention (sim only) ──
    /** Ticks between random player actions. */
    public int playerActionInterval = 10;
    /** Success probability bonus when the player favors a subfaction. */
    public float playerFavorBonus = 0.30f;
    /** Success probability penalty when the player disfavors a subfaction. */
    public float playerDisfavorPenalty = -0.30f;

    // ── Territory friction ──
    /**
     * Base friction gained per tick per pair of ESTABLISHED subfactions sharing a territory.
     * Actual gain is {@code baseFrictionPerTick * (numEstablished - 1)} - more crowding means
     * faster friction build-up for everyone.
     */
    public int baseFrictionPerTick = 2;
    /**
     * For each point of positive subfaction relationship, this much friction is drained per tick.
     * E.g. with frictionRelDrainDivisor=10 and rel=+40, drain = 40/10 = 4 per tick.
     * This allows friendly/same-parent subfactions to slow friction growth, but not eliminate it
     * entirely in crowded territories.
     */
    public int frictionRelDrainDivisor = 10;
    /**
     * Maximum friction drained per tick due to positive relations, preventing full neutralization.
     * Even very friendly subfactions will still accumulate some friction in crowded territories.
     */
    public int frictionRelDrainCap = 3;
    /** Friction threshold at which a mischief op is triggered. */
    public int frictionThreshold = 60;
    /**
     * Divisor for threat-based friction bonus. When a neighbor has higher territory
     * cohesion, extra friction is gained: {@code max(0, (theirCoh - myCoh) / divisor)}.
     * Lower values = more aggressive friction toward dominant subfactions.
     */
    public int frictionThreatDivisor = 15;
    /** Weight applied to target's home cohesion advantage (above 50) when scoring raid targets. */
    public float raidThreatWeight = 0.25f;
    /** Probability that a mischief op succeeds. */
    public float mischiefSuccessProb = 0.6f;
    /** Territory cohesion penalty inflicted on victim when mischief succeeds. */
    public int mischiefCohesionPenalty = 5;
    /** Legitimacy penalty inflicted on victim when mischief succeeds. */
    public int mischiefLegitimacyPenalty = 3;
    /** Success chance penalty applied to the target op while mischief is ongoing (e.g. 0.15 = 15%). */
    public float mischiefTargetSuccessPenalty = 0.15f;

    public static SimConfig defaults() {
        return new SimConfig();
    }

    public SimConfig copy() {
        SimConfig c = new SimConfig();
        c.minScoreThreshold = minScoreThreshold;
        c.relWeight = relWeight;
        c.powerDiffWeight = powerDiffWeight;
        c.crossFactionBaseScore = crossFactionBaseScore;
        c.mercilessBonus = mercilessBonus;
        c.opportunistWeakBonus = opportunistWeakBonus;
        c.opportunistWeakThreshold = opportunistWeakThreshold;
        c.paranoidDistrust = paranoidDistrust;
        c.paranoidDistrustThreshold = paranoidDistrustThreshold;
        c.honorBoundPenalty = honorBoundPenalty;
        c.honorBoundEnemyBonus = honorBoundEnemyBonus;
        c.honorBoundEnemyThreshold = honorBoundEnemyThreshold;
        c.charismaticPenalty = charismaticPenalty;
        c.basePowerShift = basePowerShift;
        c.mercilessBonusPower = mercilessBonusPower;
        c.relDropOnRaid = relDropOnRaid;
        c.playerRelDropOnRaid = playerRelDropOnRaid;
        c.baseFP = baseFP;
        c.fpPerPower = fpPerPower;
        c.defenderBaseFP = defenderBaseFP;
        c.defenderFpPerPower = defenderFpPerPower;
        c.combatSteepness = combatSteepness;
        c.underdogFpBonus = underdogFpBonus;
        c.meanReversionTarget = meanReversionTarget;
        c.meanReversionRate = meanReversionRate;
        c.establishBaseSuccessProb = establishBaseSuccessProb;
        c.scoutTerritorySuccessProb = scoutTerritorySuccessProb;
        c.establishTerritoryBaseSuccessProb = establishTerritoryBaseSuccessProb;
        c.patrolSuccessProb = patrolSuccessProb;
        c.sendSuppliesSuccessProb = sendSuppliesSuccessProb;
        c.sendSuppliesCohesionGain = sendSuppliesCohesionGain;
        c.sendSuppliesCohesionLoss = sendSuppliesCohesionLoss;
        c.rallySuccessProb = rallySuccessProb;
        c.rallyCohesionGain = rallyCohesionGain;
        c.rallyCohesionThreshold = rallyCohesionThreshold;
        c.highLegitimacyThreshold = highLegitimacyThreshold;
        c.territoryCohesionDecayPerTick = territoryCohesionDecayPerTick;
        c.infightingCohesionThreshold = infightingCohesionThreshold;
        c.infightingLegitimacyLoss = infightingLegitimacyLoss;
        c.expulsionCohesionThreshold = expulsionCohesionThreshold;
        c.expulsionTicksRequired = expulsionTicksRequired;
        c.expulsionLegitimacyLoss = expulsionLegitimacyLoss;
        c.civilWarCohesionThreshold = civilWarCohesionThreshold;
        c.civilWarTicksRequired = civilWarTicksRequired;
        c.pacerNudgeMin = pacerNudgeMin;
        c.pacerNudgeMax = pacerNudgeMax;
        c.tickIntervalDays = tickIntervalDays;
        c.prominencePowerThreshold = prominencePowerThreshold;
        c.extraOpCohesionCost = extraOpCohesionCost;
        c.concurrentOpDrainPerTick = concurrentOpDrainPerTick;
        c.maxConcurrentOps = maxConcurrentOps;
        c.playerActionInterval = playerActionInterval;
        c.playerFavorBonus = playerFavorBonus;
        c.playerDisfavorPenalty = playerDisfavorPenalty;
        c.baseFrictionPerTick = baseFrictionPerTick;
        c.frictionRelDrainDivisor = frictionRelDrainDivisor;
        c.frictionRelDrainCap = frictionRelDrainCap;
        c.frictionThreshold = frictionThreshold;
        c.frictionThreatDivisor = frictionThreatDivisor;
        c.raidThreatWeight = raidThreatWeight;
        c.mischiefSuccessProb = mischiefSuccessProb;
        c.mischiefCohesionPenalty = mischiefCohesionPenalty;
        c.mischiefLegitimacyPenalty = mischiefLegitimacyPenalty;
        c.mischiefTargetSuccessPenalty = mischiefTargetSuccessPenalty;
        return c;
    }
}

