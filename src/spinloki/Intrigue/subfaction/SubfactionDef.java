package spinloki.Intrigue.subfaction;

import spinloki.Intrigue.territory.BaseSlotType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable definition of a subfaction — pure identity data loaded from config.
 * The actual game faction (colors, doctrine, ships) is defined in the corresponding
 * {@code .faction} file under {@code data/world/factions/}.
 *
 * This class exists to map a subfaction's game faction ID to its parent faction ID,
 * so we can copy relationships and know which vanilla faction this subfaction is
 * affiliated with.
 */
public class SubfactionDef implements Serializable {

    private static final long serialVersionUID = 3L;

    /** The faction ID as registered in factions.csv (e.g. "intrigue_hegemony_expeditionary"). */
    public final String id;

    /** The vanilla parent faction ID (e.g. "hegemony"). */
    public final String parentFactionId;

    /** Display name for logging/debug (the .faction file has the canonical display name). */
    public final String name;

    /**
     * Preferred base slot types, in priority order. When choosing a base location,
     * slots matching earlier entries are preferred. If empty, no preference — any slot is fine.
     */
    public final List<BaseSlotType> preferredSlotTypes;

    /**
     * Weighted map of orbital station industry IDs for this subfaction's bases.
     * Key = industry ID (e.g. "orbitalstation_high"), Value = weight.
     *
     * <p>In config, this can be specified as either:</p>
     * <ul>
     *   <li>A string — e.g. {@code "stationIndustry": "orbitalstation_high"} (always that type).</li>
     *   <li>An object — e.g. {@code "stationIndustry": {"orbitalstation": 5, "orbitalstation_mid": 3}}
     *       (weighted random pick, matching vanilla's pirate base logic).</li>
     * </ul>
     *
     * <p>If empty, defaults to vanilla pirate base weights:
     * orbitalstation=5, orbitalstation_mid=3, orbitalstation_high=1.</p>
     */
    public final Map<String, Float> stationIndustryWeights;

    /**
     * Multiplier for combat fleet size spawned from this subfaction's bases.
     * Applied via {@code Stats.COMBAT_FLEET_SIZE_MULT} on the base market.
     *
     * <p>Vanilla pirate bases use ~0.5. A value of 1.0 means "same as a normal
     * size-3 market with military base". Higher values produce larger patrols.
     * Default: 0.5 (matching vanilla pirate bases).</p>
     */
    public final float fleetSizeMult;

    /**
     * Flat modifier for fleet ship quality at this subfaction's bases.
     * Applied via {@code Stats.FLEET_QUALITY_MOD} on the base market.
     *
     * <p>Higher values mean fewer D-mods. At 0 the market's base quality
     * applies (which for a size-3 hidden market is very low, producing
     * heavily D-modded ships). A value of +0.5 produces clean fleets
     * for high-tech factions; +0.25 gives a moderate improvement.</p>
     *
     * <p>Default: 0.0 (no bonus — pure doctrine-driven quality).</p>
     */
    public final float fleetQualityMod;

    /**
     * Flat modifiers for the number of patrols spawned at this subfaction's bases.
     * Applied via {@code Stats.PATROL_NUM_LIGHT_MOD}, {@code PATROL_NUM_MEDIUM_MOD},
     * and {@code PATROL_NUM_HEAVY_MOD} on the base market.
     *
     * <p>A military base normally spawns a baseline number of patrols based on
     * market size. These modifiers add to (or subtract from) those counts.
     * For example, {@code patrolExtraLight=2} means 2 additional light patrols
     * beyond the military base default.</p>
     *
     * <p>Defaults: 0 (no change — use military base defaults). Intended to be
     * adjusted dynamically in Milestone 3 as subfactions strengthen/weaken.</p>
     */
    public final int patrolExtraLight;
    public final int patrolExtraMedium;
    public final int patrolExtraHeavy;

    /**
     * Days a subfaction must scout before establishing a base. Default: 25.
     */
    public final float daysToEstablishBase;

    /**
     * Random jitter (0 to this value) added to establishment time. Default: 10.
     */
    public final float daysToEstablishJitter;

    public SubfactionDef(String id, String parentFactionId, String name,
                         List<BaseSlotType> preferredSlotTypes,
                         Map<String, Float> stationIndustryWeights,
                         float fleetSizeMult, float fleetQualityMod,
                         int patrolExtraLight, int patrolExtraMedium, int patrolExtraHeavy,
                         float daysToEstablishBase, float daysToEstablishJitter) {
        this.id = id;
        this.parentFactionId = parentFactionId;
        this.name = name;
        this.preferredSlotTypes = preferredSlotTypes != null
                ? Collections.unmodifiableList(new ArrayList<>(preferredSlotTypes))
                : Collections.emptyList();
        this.stationIndustryWeights = stationIndustryWeights != null && !stationIndustryWeights.isEmpty()
                ? Collections.unmodifiableMap(new LinkedHashMap<>(stationIndustryWeights))
                : Collections.emptyMap();
        this.fleetSizeMult = fleetSizeMult;
        this.fleetQualityMod = fleetQualityMod;
        this.patrolExtraLight = patrolExtraLight;
        this.patrolExtraMedium = patrolExtraMedium;
        this.patrolExtraHeavy = patrolExtraHeavy;
        this.daysToEstablishBase = daysToEstablishBase;
        this.daysToEstablishJitter = daysToEstablishJitter;
    }

    /**
     * Pick a station industry ID from the weighted options.
     * If no weights are configured, uses vanilla pirate base defaults.
     */
    public String pickStationIndustry(Random rand) {
        Map<String, Float> weights = stationIndustryWeights;
        if (weights.isEmpty()) {
            // Vanilla pirate base defaults
            weights = new LinkedHashMap<>();
            weights.put("orbitalstation", 5f);
            weights.put("orbitalstation_mid", 3f);
            weights.put("orbitalstation_high", 1f);
        }

        float totalWeight = 0f;
        for (float w : weights.values()) {
            totalWeight += w;
        }

        float roll = rand.nextFloat() * totalWeight;
        float cumulative = 0f;
        for (Map.Entry<String, Float> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }

        // Shouldn't happen, but fallback
        return weights.keySet().iterator().next();
    }

    // ── JSON parsing (pure Java + org.json, no Starsector) ───────────────

    /**
     * Parse all subfaction definitions from a JSON root object.
     * Pure logic — no Starsector API. Used by both game config loading and the test harness.
     */
    public static List<SubfactionDef> parseAll(JSONObject root) throws JSONException {
        JSONArray array = root.getJSONArray("subfactions");
        List<SubfactionDef> defs = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            defs.add(parseOne(array.getJSONObject(i)));
        }
        return defs;
    }

    /**
     * Parse a single subfaction definition from a JSON object.
     */
    public static SubfactionDef parseOne(JSONObject json) throws JSONException {
        String id = json.getString("id");
        String parentFactionId = json.getString("parentFactionId");
        String name = json.optString("name", id);

        List<BaseSlotType> preferredSlotTypes = new ArrayList<>();
        JSONArray slotsArray = json.optJSONArray("preferredSlotTypes");
        if (slotsArray != null) {
            for (int i = 0; i < slotsArray.length(); i++) {
                try {
                    preferredSlotTypes.add(BaseSlotType.valueOf(slotsArray.getString(i)));
                } catch (IllegalArgumentException e) {
                    System.err.println("SubfactionDef: Unknown slot type '" + slotsArray.getString(i) +
                            "' for subfaction " + id + " — ignoring");
                }
            }
        }

        Map<String, Float> stationIndustryWeights = new LinkedHashMap<>();
        if (json.has("stationIndustry")) {
            Object raw = json.get("stationIndustry");
            if (raw instanceof String) {
                stationIndustryWeights.put((String) raw, 1f);
            } else if (raw instanceof JSONObject) {
                JSONObject obj = (JSONObject) raw;
                Iterator<?> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    stationIndustryWeights.put(key, (float) obj.optDouble(key, 0));
                }
            }
        }

        float fleetSizeMult = (float) json.optDouble("fleetSizeMult", 0.5);
        float fleetQualityMod = (float) json.optDouble("fleetQualityMod", 0.0);
        int patrolExtraLight = json.optInt("patrolExtraLight", 0);
        int patrolExtraMedium = json.optInt("patrolExtraMedium", 0);
        int patrolExtraHeavy = json.optInt("patrolExtraHeavy", 0);
        float daysToEstablishBase = (float) json.optDouble("daysToEstablishBase", 25.0);
        float daysToEstablishJitter = (float) json.optDouble("daysToEstablishJitter", 10.0);

        return new SubfactionDef(id, parentFactionId, name, preferredSlotTypes,
                stationIndustryWeights, fleetSizeMult, fleetQualityMod,
                patrolExtraLight, patrolExtraMedium, patrolExtraHeavy,
                daysToEstablishBase, daysToEstablishJitter);
    }

    @Override
    public String toString() {
        return name + " [" + id + " -> " + parentFactionId + "]";
    }
}

