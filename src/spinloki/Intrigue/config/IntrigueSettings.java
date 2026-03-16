package spinloki.Intrigue.config;

import org.json.JSONException;
import org.json.JSONObject;
import spinloki.Intrigue.territory.PresenceFactor;
import spinloki.Intrigue.territory.PresenceState;

import java.io.Serializable;
import java.util.*;

/**
 * Parsed configuration from {@code intrigue_settings.json}. Pure Java + org.json only —
 * no Starsector imports, so the simulation harness can use this directly.
 *
 * <p>Immutable after construction. Load once at startup (or harness init) and pass around.</p>
 */
public class IntrigueSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Days between evaluation cycles that can trigger promotion/demotion. */
    public final int evaluationIntervalDays;

    /** Net balance magnitude required to trigger a promotion or demotion op. Symmetric. */
    public final int transitionThreshold;

    /** Per-factor-type config. */
    private final Map<PresenceFactor.FactorType, FactorDef> factorDefs;

    /** Op duration overrides by type. */
    private final Map<String, Float> opDurations;

    /** Op success chance overrides by type. */
    private final Map<String, Float> opSuccessChances;

    /** Per-state daily desertion probability. */
    private final Map<PresenceState, Float> desertionChances;

    /** Duration of the active DESERTION factor before it resolves. */
    public final float desertionDurationDays;

    // ── Desertion tuning ─────────────────────────────────────────────────

    /** Base probability of quelling a desertion (before net balance modifier). */
    public final float desertionBaseQuellChance;

    /** Per-point-of-net-balance shift toward quelling (+) or spiraling (-). */
    public final float desertionNetBalanceModifier;

    /** Minimum quell probability (clamp floor). */
    public final float desertionMinQuellChance;

    /** Maximum quell probability (clamp ceiling). */
    public final float desertionMaxQuellChance;

    // ── Timer settings ───────────────────────────────────────────────────

    /** Days between successive patrol launches for the same subfaction. */
    public final float patrolIntervalDays;

    /** Minimum days between council ops. */
    public final float councilCooldownDays;

    /** Days a subfaction can sit at ESTABLISHED before an external invasion is triggered. */
    public final float stagnationInvasionDays;

    /** Interval between entanglement-spawned op launches for the same pair. */
    public final float entanglementOpIntervalDays;

    // ── Subfaction arrival settings ──────────────────────────────────────

    /** Target number of subfactions per territory. Arrivals stop when this is reached. */
    public final int arrivalTargetCount;

    /** Per-vacancy probability of a new subfaction arriving each evaluation cycle. */
    public final float arrivalChancePerVacancy;

    // ── Entanglement trigger probabilities ───────────────────────────────

    /** Chance that a raid victim seeks a hired protector. */
    public final float hiredProtectionChance;

    /** Chance that a raid victim seeks a shared-enemy pact. */
    public final float sharedEnemyPactChance;

    /** Base chance of forming a retaliation coalition. */
    public final float retaliationBaseChance;

    /** Retaliation chance when the raider is DOMINANT. */
    public final float retaliationDominantChance;

    /** Daily chance that a third party initiates proxy support during a war. */
    public final float proxySupportDailyChance;

    /** Duration of a Hired Protection entanglement (days). */
    public final float hiredProtectionDurationDays;

    /** Duration of a Retaliation Coalition entanglement (days). */
    public final float retaliationDurationDays;

    /** Duration of a Proxy Support entanglement (days). */
    public final float proxySupportDurationDays;

    /** Duration of a Territorial War created by council breakdown. */
    public final float councilBreakdownWarDurationDays;

    /** Duration of a Territorial War created by catastrophic council breakdown. */
    public final float councilCatastropheWarDurationDays;

    /** Duration of a Hired Protection pact created by selective council pacts. */
    public final float councilPactDurationDays;

    // ── Council outcome weights ──────────────────────────────────────────

    public final float councilDetenteBase;
    public final float councilDetentePacificationMult;
    public final float councilSelectivePactsBase;
    public final float councilSelectivePactsPacificationMult;
    public final float councilBreakdownBase;
    public final float councilBreakdownDisruptionMult;
    public final float councilCatastropheBase;
    public final float councilCatastropheDisruptionMult;

    // ── Op interaction multipliers ───────────────────────────────────────

    /** WAR_RAID disrupts an enemy PATROL by this multiplier on success chance. */
    public final float warRaidPatrolDisruption;

    /** PROTECTION_PATROL defends against RAID/WAR_RAID by this multiplier. */
    public final float protectionPatrolDefense;

    /** ARMS_SHIPMENT boosts combat op success by this multiplier. */
    public final float armsShipmentBoost;

    // ── Hostility settings ───────────────────────────────────────────────

    /** Per-day probability that a VOLATILE faction pair starts hostilities. */
    public final float volatileDailyChance;

    /** Minimum duration of a dynamic hostility event (days). */
    public final float volatileMinDays;

    /** Maximum duration of a dynamic hostility event (days). */
    public final float volatileMaxDays;

    /** FactionAPI relationship value used for hostile subfaction pairs. */
    public final float hostileRelationValue;

    /** FactionAPI relationship value used for neutral subfaction pairs. */
    public final float neutralRelationValue;

    // ── Factor definition ────────────────────────────────────────────────

    /**
     * Configuration for a single factor type.
     * Either uses a flat weight or state-dependent weights (mutually exclusive).
     */
    public static class FactorDef implements Serializable {
        private static final long serialVersionUID = 1L;

        public final PresenceFactor.Polarity polarity;

        /** Flat weight (used if weightsByState is empty). */
        public final int weight;

        /** State-dependent weights. Empty map = use flat weight. */
        public final Map<PresenceState, Integer> weightsByState;

        /** Duration in days for expiring factors. -1 = permanent/conditional. */
        public final float durationDays;

        public FactorDef(PresenceFactor.Polarity polarity, int weight,
                         Map<PresenceState, Integer> weightsByState, float durationDays) {
            this.polarity = polarity;
            this.weight = weight;
            this.weightsByState = Collections.unmodifiableMap(weightsByState);
            this.durationDays = durationDays;
        }

        /** Get the effective weight for a given presence state. */
        public int getWeight(PresenceState state) {
            if (!weightsByState.isEmpty()) {
                Integer w = weightsByState.get(state);
                return w != null ? w : 0;
            }
            return weight;
        }
    }

    // ── Construction ─────────────────────────────────────────────────────

    private IntrigueSettings(int evaluationIntervalDays, int transitionThreshold,
                             Map<PresenceFactor.FactorType, FactorDef> factorDefs,
                             Map<String, Float> opDurations,
                             Map<String, Float> opSuccessChances,
                             Map<PresenceState, Float> desertionChances,
                             float desertionDurationDays,
                             float desertionBaseQuellChance, float desertionNetBalanceModifier,
                             float desertionMinQuellChance, float desertionMaxQuellChance,
                             float patrolIntervalDays, float councilCooldownDays,
                             float stagnationInvasionDays, float entanglementOpIntervalDays,
                             int arrivalTargetCount, float arrivalChancePerVacancy,
                             float hiredProtectionChance, float sharedEnemyPactChance,
                             float retaliationBaseChance, float retaliationDominantChance,
                             float proxySupportDailyChance,
                             float hiredProtectionDurationDays, float retaliationDurationDays,
                             float proxySupportDurationDays,
                             float councilBreakdownWarDurationDays,
                             float councilCatastropheWarDurationDays,
                             float councilPactDurationDays,
                             float councilDetenteBase, float councilDetentePacificationMult,
                             float councilSelectivePactsBase, float councilSelectivePactsPacificationMult,
                             float councilBreakdownBase, float councilBreakdownDisruptionMult,
                             float councilCatastropheBase, float councilCatastropheDisruptionMult,
                             float warRaidPatrolDisruption, float protectionPatrolDefense,
                             float armsShipmentBoost,
                             float volatileDailyChance, float volatileMinDays, float volatileMaxDays,
                             float hostileRelationValue, float neutralRelationValue) {
        this.evaluationIntervalDays = evaluationIntervalDays;
        this.transitionThreshold = transitionThreshold;
        this.factorDefs = Collections.unmodifiableMap(factorDefs);
        this.opDurations = Collections.unmodifiableMap(opDurations);
        this.opSuccessChances = Collections.unmodifiableMap(opSuccessChances);
        this.desertionChances = Collections.unmodifiableMap(desertionChances);
        this.desertionDurationDays = desertionDurationDays;
        this.desertionBaseQuellChance = desertionBaseQuellChance;
        this.desertionNetBalanceModifier = desertionNetBalanceModifier;
        this.desertionMinQuellChance = desertionMinQuellChance;
        this.desertionMaxQuellChance = desertionMaxQuellChance;
        this.patrolIntervalDays = patrolIntervalDays;
        this.councilCooldownDays = councilCooldownDays;
        this.stagnationInvasionDays = stagnationInvasionDays;
        this.entanglementOpIntervalDays = entanglementOpIntervalDays;
        this.arrivalTargetCount = arrivalTargetCount;
        this.arrivalChancePerVacancy = arrivalChancePerVacancy;
        this.hiredProtectionChance = hiredProtectionChance;
        this.sharedEnemyPactChance = sharedEnemyPactChance;
        this.retaliationBaseChance = retaliationBaseChance;
        this.retaliationDominantChance = retaliationDominantChance;
        this.proxySupportDailyChance = proxySupportDailyChance;
        this.hiredProtectionDurationDays = hiredProtectionDurationDays;
        this.retaliationDurationDays = retaliationDurationDays;
        this.proxySupportDurationDays = proxySupportDurationDays;
        this.councilBreakdownWarDurationDays = councilBreakdownWarDurationDays;
        this.councilCatastropheWarDurationDays = councilCatastropheWarDurationDays;
        this.councilPactDurationDays = councilPactDurationDays;
        this.councilDetenteBase = councilDetenteBase;
        this.councilDetentePacificationMult = councilDetentePacificationMult;
        this.councilSelectivePactsBase = councilSelectivePactsBase;
        this.councilSelectivePactsPacificationMult = councilSelectivePactsPacificationMult;
        this.councilBreakdownBase = councilBreakdownBase;
        this.councilBreakdownDisruptionMult = councilBreakdownDisruptionMult;
        this.councilCatastropheBase = councilCatastropheBase;
        this.councilCatastropheDisruptionMult = councilCatastropheDisruptionMult;
        this.warRaidPatrolDisruption = warRaidPatrolDisruption;
        this.protectionPatrolDefense = protectionPatrolDefense;
        this.armsShipmentBoost = armsShipmentBoost;
        this.volatileDailyChance = volatileDailyChance;
        this.volatileMinDays = volatileMinDays;
        this.volatileMaxDays = volatileMaxDays;
        this.hostileRelationValue = hostileRelationValue;
        this.neutralRelationValue = neutralRelationValue;
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    /**
     * Parse settings from a JSON string (the contents of intrigue_settings.json).
     */
    public static IntrigueSettings parse(String jsonString) throws JSONException {
        JSONObject root = new JSONObject(jsonString);

        int evalInterval = root.optInt("evaluationIntervalDays", 60);
        int threshold = root.optInt("transitionThreshold", 3);

        // Parse factor definitions
        Map<PresenceFactor.FactorType, FactorDef> factorDefs = new LinkedHashMap<>();
        JSONObject factorsJson = root.optJSONObject("factors");
        if (factorsJson != null) {
            Iterator<String> factorKeys = factorsJson.keys();
            while (factorKeys.hasNext()) {
                String key = factorKeys.next();
                PresenceFactor.FactorType type;
                try {
                    type = PresenceFactor.FactorType.valueOf(key);
                } catch (IllegalArgumentException e) {
                    continue; // Skip unknown factor types
                }

                JSONObject fj = factorsJson.getJSONObject(key);
                PresenceFactor.Polarity polarity =
                        PresenceFactor.Polarity.valueOf(fj.getString("polarity"));
                int weight = fj.optInt("weight", 1);
                float duration = (float) fj.optDouble("durationDays", -1.0);

                Map<PresenceState, Integer> weightsByState = new LinkedHashMap<>();
                JSONObject wbs = fj.optJSONObject("weightsByState");
                if (wbs != null) {
                    Iterator<String> stateKeys = wbs.keys();
                    while (stateKeys.hasNext()) {
                        String stateKey = stateKeys.next();
                        try {
                            PresenceState ps = PresenceState.valueOf(stateKey);
                            weightsByState.put(ps, wbs.getInt(stateKey));
                        } catch (IllegalArgumentException e) {
                            // Skip unknown states
                        }
                    }
                }

                factorDefs.put(type, new FactorDef(polarity, weight, weightsByState, duration));
            }
        }

        // Parse op durations
        Map<String, Float> opDurations = new LinkedHashMap<>();
        JSONObject durJson = root.optJSONObject("opDurations");
        if (durJson != null) {
            Iterator<String> durKeys = durJson.keys();
            while (durKeys.hasNext()) {
                String key = durKeys.next();
                opDurations.put(key, (float) durJson.getDouble(key));
            }
        }

        // Parse op success chances
        Map<String, Float> opChances = new LinkedHashMap<>();
        JSONObject chanceJson = root.optJSONObject("opSuccessChances");
        if (chanceJson != null) {
            Iterator<String> chanceKeys = chanceJson.keys();
            while (chanceKeys.hasNext()) {
                String key = chanceKeys.next();
                opChances.put(key, (float) chanceJson.getDouble(key));
            }
        }

        // Parse desertion chances
        Map<PresenceState, Float> desertionChances = new LinkedHashMap<>();
        JSONObject desJson = root.optJSONObject("desertionChances");
        if (desJson != null) {
            Iterator<String> desKeys = desJson.keys();
            while (desKeys.hasNext()) {
                String key = desKeys.next();
                try {
                    PresenceState ps = PresenceState.valueOf(key);
                    desertionChances.put(ps, (float) desJson.getDouble(key));
                } catch (IllegalArgumentException e) {
                    // Skip unknown states
                }
            }
        }

        float desertionDuration = (float) root.optDouble("desertionDurationDays", 30.0);

        // Parse desertion tuning
        JSONObject desertion = root.optJSONObject("desertion");
        float desertionBaseQuellChance = desertion != null ? (float) desertion.optDouble("baseQuellChance", 0.5) : 0.5f;
        float desertionNetBalanceModifier = desertion != null ? (float) desertion.optDouble("netBalanceModifier", 0.1) : 0.1f;
        float desertionMinQuellChance = desertion != null ? (float) desertion.optDouble("minQuellChance", 0.1) : 0.1f;
        float desertionMaxQuellChance = desertion != null ? (float) desertion.optDouble("maxQuellChance", 0.9) : 0.9f;

        // Parse timer settings
        JSONObject timers = root.optJSONObject("timers");
        float patrolIntervalDays = timers != null ? (float) timers.optDouble("patrolIntervalDays", 10.0) : 10f;
        float councilCooldownDays = timers != null ? (float) timers.optDouble("councilCooldownDays", 120.0) : 120f;
        float stagnationInvasionDays = timers != null ? (float) timers.optDouble("stagnationInvasionDays", 180.0) : 180f;
        float entanglementOpIntervalDays = timers != null ? (float) timers.optDouble("entanglementOpIntervalDays", 30.0) : 30f;

        // Parse arrival settings
        JSONObject arrival = root.optJSONObject("arrival");
        int arrivalTargetCount = arrival != null ? arrival.optInt("targetCount", 4) : 4;
        float arrivalChancePerVacancy = arrival != null ? (float) arrival.optDouble("chancePerVacancy", 0.3) : 0.3f;

        // Parse entanglement trigger settings
        JSONObject triggers = root.optJSONObject("entanglementTriggers");
        float hiredProtectionChance = triggers != null ? (float) triggers.optDouble("hiredProtectionChance", 0.5) : 0.5f;
        float sharedEnemyPactChance = triggers != null ? (float) triggers.optDouble("sharedEnemyPactChance", 0.3) : 0.3f;
        float retaliationBaseChance = triggers != null ? (float) triggers.optDouble("retaliationBaseChance", 0.15) : 0.15f;
        float retaliationDominantChance = triggers != null ? (float) triggers.optDouble("retaliationDominantChance", 0.40) : 0.40f;
        float proxySupportDailyChance = triggers != null ? (float) triggers.optDouble("proxySupportDailyChance", 0.01) : 0.01f;
        float hiredProtectionDurationDays = triggers != null ? (float) triggers.optDouble("hiredProtectionDurationDays", 75.0) : 75f;
        float retaliationDurationDays = triggers != null ? (float) triggers.optDouble("retaliationDurationDays", 45.0) : 45f;
        float proxySupportDurationDays = triggers != null ? (float) triggers.optDouble("proxySupportDurationDays", 60.0) : 60f;
        float councilBreakdownWarDurationDays = triggers != null ? (float) triggers.optDouble("councilBreakdownWarDurationDays", 120.0) : 120f;
        float councilCatastropheWarDurationDays = triggers != null ? (float) triggers.optDouble("councilCatastropheWarDurationDays", 90.0) : 90f;
        float councilPactDurationDays = triggers != null ? (float) triggers.optDouble("councilPactDurationDays", 90.0) : 90f;

        // Parse council outcome weights
        JSONObject council = root.optJSONObject("councilOutcomeWeights");
        float councilDetenteBase = council != null ? (float) council.optDouble("detenteBase", 0.05) : 0.05f;
        float councilDetentePacificationMult = council != null ? (float) council.optDouble("detentePacificationMult", 0.30) : 0.30f;
        float councilSelectivePactsBase = council != null ? (float) council.optDouble("selectivePactsBase", 0.10) : 0.10f;
        float councilSelectivePactsPacificationMult = council != null ? (float) council.optDouble("selectivePactsPacificationMult", 0.20) : 0.20f;
        float councilBreakdownBase = council != null ? (float) council.optDouble("breakdownBase", 0.15) : 0.15f;
        float councilBreakdownDisruptionMult = council != null ? (float) council.optDouble("breakdownDisruptionMult", 0.30) : 0.30f;
        float councilCatastropheBase = council != null ? (float) council.optDouble("catastropheBase", 0.05) : 0.05f;
        float councilCatastropheDisruptionMult = council != null ? (float) council.optDouble("catastropheDisruptionMult", 0.15) : 0.15f;

        // Parse op interaction multipliers
        JSONObject opInteract = root.optJSONObject("opInteractions");
        float warRaidPatrolDisruption = opInteract != null ? (float) opInteract.optDouble("warRaidPatrolDisruption", 0.5) : 0.5f;
        float protectionPatrolDefense = opInteract != null ? (float) opInteract.optDouble("protectionPatrolDefense", 0.7) : 0.7f;
        float armsShipmentBoost = opInteract != null ? (float) opInteract.optDouble("armsShipmentBoost", 1.3) : 1.3f;

        // Parse hostility settings
        JSONObject hostility = root.optJSONObject("hostility");
        float volatileDailyChance = hostility != null ? (float) hostility.optDouble("volatileDailyChance", 0.005) : 0.005f;
        float volatileMinDays = hostility != null ? (float) hostility.optDouble("volatileMinDays", 90.0) : 90f;
        float volatileMaxDays = hostility != null ? (float) hostility.optDouble("volatileMaxDays", 180.0) : 180f;
        float hostileRelationValue = hostility != null ? (float) hostility.optDouble("hostileRelationValue", -0.75) : -0.75f;
        float neutralRelationValue = hostility != null ? (float) hostility.optDouble("neutralRelationValue", 0.0) : 0.0f;

        return new IntrigueSettings(evalInterval, threshold, factorDefs, opDurations, opChances,
                desertionChances, desertionDuration,
                desertionBaseQuellChance, desertionNetBalanceModifier,
                desertionMinQuellChance, desertionMaxQuellChance,
                patrolIntervalDays, councilCooldownDays,
                stagnationInvasionDays, entanglementOpIntervalDays,
                arrivalTargetCount, arrivalChancePerVacancy,
                hiredProtectionChance, sharedEnemyPactChance,
                retaliationBaseChance, retaliationDominantChance,
                proxySupportDailyChance,
                hiredProtectionDurationDays, retaliationDurationDays,
                proxySupportDurationDays,
                councilBreakdownWarDurationDays, councilCatastropheWarDurationDays,
                councilPactDurationDays,
                councilDetenteBase, councilDetentePacificationMult,
                councilSelectivePactsBase, councilSelectivePactsPacificationMult,
                councilBreakdownBase, councilBreakdownDisruptionMult,
                councilCatastropheBase, councilCatastropheDisruptionMult,
                warRaidPatrolDisruption, protectionPatrolDefense, armsShipmentBoost,
                volatileDailyChance, volatileMinDays, volatileMaxDays,
                hostileRelationValue, neutralRelationValue);
    }

    // ── Accessors ────────────────────────────────────────────────────────

    /** Get the factor definition for a given type, or null if not configured. */
    public FactorDef getFactorDef(PresenceFactor.FactorType type) {
        return factorDefs.get(type);
    }

    /** Get all configured factor definitions. */
    public Map<PresenceFactor.FactorType, FactorDef> getFactorDefs() {
        return factorDefs;
    }

    /** Get op duration for a given op type name. Falls back to a default if not configured. */
    public float getOpDuration(String opType, float defaultDays) {
        Float d = opDurations.get(opType);
        return d != null ? d : defaultDays;
    }

    /** Get op success chance for a given op type name. Falls back to a default if not configured. */
    public float getOpSuccessChance(String opType, float defaultChance) {
        Float c = opSuccessChances.get(opType);
        return c != null ? c : defaultChance;
    }

    /** Get daily desertion probability for a given presence state. 0 if not configured. */
    public float getDesertionChance(PresenceState state) {
        Float c = desertionChances.get(state);
        return c != null ? c : 0f;
    }
}
