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

    // ── Pacing ──
    public int pacerNudgeMin = -1;
    public int pacerNudgeMax = 1;
    public float tickIntervalDays = 7f;

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
        c.pacerNudgeMin = pacerNudgeMin;
        c.pacerNudgeMax = pacerNudgeMax;
        c.tickIntervalDays = tickIntervalDays;
        return c;
    }
}

