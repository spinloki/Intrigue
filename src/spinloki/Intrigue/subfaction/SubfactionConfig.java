package spinloki.Intrigue.subfaction;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spinloki.Intrigue.territory.BaseSlotType;

/**
 * Loads subfaction definitions from {@code data/config/subfactions.json}.
 *
 * The JSON maps each subfaction ID to its parent faction. The actual faction
 * identity (colors, doctrine, ship pools) lives in the corresponding
 * {@code .faction} file, loaded automatically by the engine via {@code factions.csv}.
 */
public class SubfactionConfig {

    private static final Logger log = Global.getLogger(SubfactionConfig.class);

    private static final String CONFIG_PATH = "data/config/subfactions.json";

    /**
     * Load all subfaction definitions from the config file.
     */
    public static List<SubfactionDef> load() throws IOException, JSONException {
        JSONObject root = Global.getSettings().loadJSON(CONFIG_PATH);
        JSONArray array = root.getJSONArray("subfactions");

        List<SubfactionDef> defs = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            defs.add(parseDef(array.getJSONObject(i)));
        }

        log.info("Loaded " + defs.size() + " subfaction definitions from " + CONFIG_PATH);
        return defs;
    }

    private static SubfactionDef parseDef(JSONObject json) throws JSONException {
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
                    log.warn("Unknown slot type '" + slotsArray.getString(i) +
                            "' for subfaction " + id + " — ignoring");
                }
            }
        }

        // stationIndustry can be a string (single type) or object (weighted map)
        Map<String, Float> stationIndustryWeights = new LinkedHashMap<>();
        if (json.has("stationIndustry")) {
            Object raw = json.get("stationIndustry");
            if (raw instanceof String) {
                // Single type, weight 1 = always this type
                stationIndustryWeights.put((String) raw, 1f);
            } else if (raw instanceof JSONObject) {
                // Weighted map: {"orbitalstation": 5, "orbitalstation_mid": 3}
                JSONObject obj = (JSONObject) raw;
                Iterator<?> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    stationIndustryWeights.put(key, (float) obj.optDouble(key, 0));
                }
            }
        }

        // Fleet size multiplier — applied to the base market's COMBAT_FLEET_SIZE_MULT stat.
        // Default 0.5 matches vanilla pirate bases. Higher = bigger patrol fleets.
        float fleetSizeMult = (float) json.optDouble("fleetSizeMult", 0.5);

        // Fleet quality modifier — applied to the base market's FLEET_QUALITY_MOD stat.
        // Default 0.0 = no bonus (market-size-driven quality, which is low for size 3).
        // Positive values reduce D-mods: 0.25 = moderate, 0.5 = mostly clean ships.
        float fleetQualityMod = (float) json.optDouble("fleetQualityMod", 0.0);

        // Patrol count modifiers — flat additions to the number of patrols spawned.
        // Military base has its own baseline; these add on top of that.
        // Default 0 = no change. Intended for future dynamic adjustment (Milestone 3).
        int patrolExtraLight = json.optInt("patrolExtraLight", 0);
        int patrolExtraMedium = json.optInt("patrolExtraMedium", 0);
        int patrolExtraHeavy = json.optInt("patrolExtraHeavy", 0);

        // Establishment timing — how long this subfaction scouts before building a base.
        float daysToEstablishBase = (float) json.optDouble("daysToEstablishBase", 25.0);
        float daysToEstablishJitter = (float) json.optDouble("daysToEstablishJitter", 10.0);

        return new SubfactionDef(id, parentFactionId, name, preferredSlotTypes,
                stationIndustryWeights, fleetSizeMult, fleetQualityMod,
                patrolExtraLight, patrolExtraMedium, patrolExtraHeavy,
                daysToEstablishBase, daysToEstablishJitter);
    }
}

