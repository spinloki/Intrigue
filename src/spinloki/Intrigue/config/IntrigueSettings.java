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
                             float desertionDurationDays) {
        this.evaluationIntervalDays = evaluationIntervalDays;
        this.transitionThreshold = transitionThreshold;
        this.factorDefs = Collections.unmodifiableMap(factorDefs);
        this.opDurations = Collections.unmodifiableMap(opDurations);
        this.opSuccessChances = Collections.unmodifiableMap(opSuccessChances);
        this.desertionChances = Collections.unmodifiableMap(desertionChances);
        this.desertionDurationDays = desertionDurationDays;
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

        return new IntrigueSettings(evalInterval, threshold, factorDefs, opDurations, opChances,
                desertionChances, desertionDuration);
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
