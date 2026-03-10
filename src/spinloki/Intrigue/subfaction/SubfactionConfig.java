package spinloki.Intrigue.subfaction;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

        return new SubfactionDef(id, parentFactionId, name);
    }
}

