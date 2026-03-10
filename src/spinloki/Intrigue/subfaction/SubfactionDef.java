package spinloki.Intrigue.subfaction;

import spinloki.Intrigue.territory.BaseSlotType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    private static final long serialVersionUID = 2L;

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

    public SubfactionDef(String id, String parentFactionId, String name,
                         List<BaseSlotType> preferredSlotTypes,
                         Map<String, Float> stationIndustryWeights,
                         float fleetSizeMult, float fleetQualityMod,
                         int patrolExtraLight, int patrolExtraMedium, int patrolExtraHeavy) {
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

    @Override
    public String toString() {
        return name + " [" + id + " -> " + parentFactionId + "]";
    }
}

