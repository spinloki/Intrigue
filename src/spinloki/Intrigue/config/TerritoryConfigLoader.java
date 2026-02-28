package spinloki.Intrigue.config;

import com.fs.starfarer.api.Global;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Loads data/config/intrigue_territories.json and produces a {@link TerritoryConfig}.
 *
 * Uses Starsector's {@code Global.getSettings().loadJSON()} so the file is
 * resolved relative to the mod's data/ directory.
 */
public class TerritoryConfigLoader {

    private static final Logger log = Logger.getLogger(TerritoryConfigLoader.class.getName());
    public static final String CONFIG_PATH = "data/config/intrigue_territories.json";

    /**
     * Load and parse the territory config. Returns an empty config on failure
     * (never null).
     */
    public static TerritoryConfig load() {
        TerritoryConfig config = new TerritoryConfig();
        config.territories = new ArrayList<>();

        try {
            JSONObject root = Global.getSettings().loadJSON(CONFIG_PATH);
            config.territoryDecayPerTick = root.optInt("territoryDecayPerTick", 2);

            JSONArray arr = root.getJSONArray("territories");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject tJson = arr.getJSONObject(i);
                TerritoryConfig.TerritoryDef def = parseTerritoryDef(tJson);
                config.territories.add(def);
            }

            log.info("Loaded " + config.territories.size() + " territory definitions from " + CONFIG_PATH);

        } catch (IOException | JSONException e) {
            log.severe("Failed to load territory config from " + CONFIG_PATH + ": " + e.getMessage());
        }

        return config;
    }

    private static TerritoryConfig.TerritoryDef parseTerritoryDef(JSONObject json) throws JSONException {
        TerritoryConfig.TerritoryDef def = new TerritoryConfig.TerritoryDef();
        def.territoryId = json.getString("territoryId");
        def.name = json.getString("name");

        String tierStr = json.optString("tier", "LOW");
        try {
            def.tier = TerritoryConfig.Tier.valueOf(tierStr);
        } catch (IllegalArgumentException e) {
            def.tier = TerritoryConfig.Tier.LOW;
        }

        def.plotHook = json.optString("plotHook", null);
        def.numConstellations = json.optInt("numConstellations", 1);

        def.interestedFactions = new ArrayList<>();
        if (json.has("interestedFactions")) {
            JSONArray fArr = json.getJSONArray("interestedFactions");
            for (int j = 0; j < fArr.length(); j++) {
                def.interestedFactions.add(fArr.getString(j));
            }
        }

        return def;
    }
}

