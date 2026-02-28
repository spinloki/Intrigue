package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.ops.OpOutcome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Default outcome resolver that uses {@link SimConfig} probabilities and
 * a sigmoid model for combat. Supports per-subfaction probability modifiers
 * (e.g. from simulated player intervention) and verbose probability logging.
 */
public class DefaultOutcomeResolver implements OpOutcomeResolver {

    private final Random rng;
    private final SimConfig config;
    private final boolean verbose;
    private final Map<String, Float> modifiers = new LinkedHashMap<>();

    public DefaultOutcomeResolver(Random rng, SimConfig config) {
        this.rng = rng;
        this.config = config;
        this.verbose = "true".equals(System.getProperty("intrigue.verbose"));
    }

    // ── Modifier API ──

    @Override
    public void setSubfactionModifier(String subfactionId, float modifier) {
        modifiers.put(subfactionId, modifier);
    }

    @Override
    public float getSubfactionModifier(String subfactionId) {
        return modifiers.getOrDefault(subfactionId, 0f);
    }

    @Override
    public void clearModifiers() {
        modifiers.clear();
    }

    // ── Resolution methods ──

    @Override
    public OpOutcome resolveRaid(int attackerCohesion, int defenderCohesion, boolean attackerMerciless) {
        // Raid uses sigmoid - modifier not applied here (no subfaction context)
        int attackerFP = config.baseFP + (int) (attackerCohesion * config.fpPerPower);
        int defenderFP = config.defenderBaseFP + (int) (defenderCohesion * config.defenderFpPerPower);

        int fpGap = attackerFP - defenderFP;
        if (fpGap > 0) {
            defenderFP += (int) (fpGap * config.underdogFpBonus);
        }

        double diff = attackerFP - defenderFP;
        double prob = 1.0 / (1.0 + Math.exp(-config.combatSteepness * diff));
        double roll = rng.nextDouble();
        OpOutcome result = roll < prob ? OpOutcome.SUCCESS : OpOutcome.FAILURE;

        if (verbose) {
            System.out.printf("    [prob] Raid: atkFP=%d defFP=%d sigmoid=%.3f roll=%.3f -> %s%n",
                    attackerFP, defenderFP, prob, roll, result);
        }
        return result;
    }

    @Override
    public OpOutcome resolveEstablishBase(IntrigueSubfaction subfaction) {
        return resolveFlat("Establish Base", subfaction, config.establishBaseSuccessProb);
    }

    @Override
    public OpOutcome resolveScoutTerritory(IntrigueSubfaction subfaction, String territoryId) {
        return resolveFlat("Scout Territory", subfaction, config.scoutTerritorySuccessProb);
    }

    @Override
    public OpOutcome resolveEstablishTerritoryBase(IntrigueSubfaction subfaction, String territoryId) {
        return resolveFlat("Establish Terr. Base", subfaction, config.establishTerritoryBaseSuccessProb);
    }

    @Override
    public OpOutcome resolvePatrol(IntrigueSubfaction subfaction, String territoryId) {
        return resolveFlat("Patrol", subfaction, config.patrolSuccessProb);
    }

    @Override
    public OpOutcome resolveSendSupplies(IntrigueSubfaction subfaction, String territoryId) {
        return resolveFlat("Send Supplies", subfaction, config.sendSuppliesSuccessProb);
    }

    @Override
    public OpOutcome resolveRally(IntrigueSubfaction subfaction) {
        return resolveFlat("Rally", subfaction, config.rallySuccessProb);
    }

    @Override
    public OpOutcome resolveMischief(IntrigueSubfaction initiator, IntrigueSubfaction victim) {
        return resolveFlat("Mischief", initiator, config.mischiefSuccessProb);
    }

    // ── Shared helper ──

    /**
     * Resolve a flat-probability op with modifier support and verbose logging.
     *
     * Calculation:
     *   effective = clamp(baseProb + subfactionModifier, 0.0, 1.0)
     *   roll a uniform random [0,1)
     *   SUCCESS if roll < effective, else FAILURE
     */
    private OpOutcome resolveFlat(String opName, IntrigueSubfaction subfaction, float baseProb) {
        String sfId = subfaction != null ? subfaction.getSubfactionId() : "?";
        float modifier = getSubfactionModifier(sfId);
        float effective = Math.max(0f, Math.min(1f, baseProb + modifier));
        float roll = rng.nextFloat();
        OpOutcome result = roll < effective ? OpOutcome.SUCCESS : OpOutcome.FAILURE;

        if (verbose) {
            String modStr = modifier == 0f ? "none" :
                    String.format("%+.0f%% (%s)", modifier * 100, modifier > 0 ? "player favor" : "player disfavor");
            System.out.printf("    [prob] %-20s %-20s base=%.0f%%  modifier=%s  effective=%.0f%%  roll=%.3f -> %s%n",
                    opName, sfId, baseProb * 100, modStr, effective * 100, roll, result);
        }

        return result;
    }
}
