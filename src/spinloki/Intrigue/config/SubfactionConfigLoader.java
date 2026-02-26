package spinloki.Intrigue.config;

import com.fs.starfarer.api.Global;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Loads data/config/intrigue_subfactions.json and produces a {@link SubfactionConfig}.
 *
 * Uses Starsector's {@code Global.getSettings().loadJSON()} so the file is
 * resolved relative to the mod's data/ directory.
 */
public class SubfactionConfigLoader {

    private static final Logger log = Logger.getLogger(SubfactionConfigLoader.class.getName());
    public static final String CONFIG_PATH = "data/config/intrigue_subfactions.json";

    /**
     * Load and parse the subfaction config. Returns an empty config on failure
     * (never null).
     */
    public static SubfactionConfig load() {
        SubfactionConfig config = new SubfactionConfig();
        config.subfactions = new ArrayList<>();

        try {
            JSONObject root = Global.getSettings().loadJSON(CONFIG_PATH);
            JSONArray arr = root.getJSONArray("subfactions");

            for (int i = 0; i < arr.length(); i++) {
                JSONObject sfJson = arr.getJSONObject(i);
                SubfactionConfig.SubfactionDef def = parseSubfactionDef(sfJson);
                config.subfactions.add(def);
            }

            log.info("Loaded " + config.subfactions.size() + " subfaction definitions from " + CONFIG_PATH);

        } catch (IOException | JSONException e) {
            log.severe("Failed to load subfaction config from " + CONFIG_PATH + ": " + e.getMessage());
        }

        return config;
    }

    private static SubfactionConfig.SubfactionDef parseSubfactionDef(JSONObject json) throws JSONException {
        SubfactionConfig.SubfactionDef def = new SubfactionConfig.SubfactionDef();
        def.subfactionId = json.getString("subfactionId");
        def.name = json.getString("name");
        def.factionId = json.getString("factionId");
        def.homeMarketId = json.getString("homeMarketId");
        def.power = json.optInt("power", 50);
        def.members = new ArrayList<>();

        JSONArray membersArr = json.getJSONArray("members");
        for (int j = 0; j < membersArr.length(); j++) {
            def.members.add(parseMemberDef(membersArr.getJSONObject(j)));
        }

        return def;
    }

    private static SubfactionConfig.MemberDef parseMemberDef(JSONObject json) throws JSONException {
        SubfactionConfig.MemberDef mDef = new SubfactionConfig.MemberDef();
        mDef.role = json.getString("role");
        mDef.firstName = json.getString("firstName");
        mDef.lastName = json.getString("lastName");
        mDef.gender = json.optString("gender", "MALE");
        mDef.portraitId = json.optString("portraitId", null);
        mDef.rankId = json.optString("rankId", null);
        mDef.postId = json.optString("postId", null);
        mDef.bonus = json.optString("bonus", null);

        mDef.traits = new ArrayList<>();
        if (json.has("traits")) {
            JSONArray traitsArr = json.getJSONArray("traits");
            for (int k = 0; k < traitsArr.length(); k++) {
                mDef.traits.add(traitsArr.getString(k));
            }
        }

        return mDef;
    }
}

