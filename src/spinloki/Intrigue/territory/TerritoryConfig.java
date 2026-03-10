package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses data/config/territories.json into strongly-typed POJOs.
 * All randomization is deferred to generation time — this class is pure config parsing.
 */
public class TerritoryConfig {

    private static final Logger log = Global.getLogger(TerritoryConfig.class);
    private static final String CONFIG_PATH = "data/config/territories.json";

    // ── Top-level data models ────────────────────────────────────────────

    public static class TerritoryDef {
        public final String id;
        public final String name;
        public final List<SystemDef> systems;

        public TerritoryDef(String id, String name, List<SystemDef> systems) {
            this.id = id;
            this.name = name;
            this.systems = systems;
        }
    }

    public static class SystemDef {
        public final String id;
        public final String name;
        public final StarAge age;
        public final String starType;
        public final float starRadius;
        public final float coronaRadius;
        public final List<PlanetDef> planets;
        public final List<AsteroidBeltDef> asteroidBelts;
        public final int stableLocationCount;

        public SystemDef(String id, String name, StarAge age, String starType,
                         float starRadius, float coronaRadius,
                         List<PlanetDef> planets, List<AsteroidBeltDef> asteroidBelts,
                         int stableLocationCount) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.starType = starType;
            this.starRadius = starRadius;
            this.coronaRadius = coronaRadius;
            this.planets = planets;
            this.asteroidBelts = asteroidBelts;
            this.stableLocationCount = stableLocationCount;
        }
    }

    public static class PlanetDef {
        public final String id;
        public final String name;
        public final String type;
        public final float orbitRadius;
        public final float orbitDays;
        public final float orbitAngle;
        public final float radius;
        public final List<String> conditions;

        public PlanetDef(String id, String name, String type,
                         float orbitRadius, float orbitDays, float orbitAngle, float radius,
                         List<String> conditions) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.orbitRadius = orbitRadius;
            this.orbitDays = orbitDays;
            this.orbitAngle = orbitAngle;
            this.radius = radius;
            this.conditions = conditions;
        }
    }

    public static class AsteroidBeltDef {
        public final float orbitRadius;
        public final float width;
        public final int count;
        public final float orbitDaysMin;
        public final float orbitDaysMax;
        public final String name;

        public AsteroidBeltDef(float orbitRadius, float width, int count,
                               float orbitDaysMin, float orbitDaysMax, String name) {
            this.orbitRadius = orbitRadius;
            this.width = width;
            this.count = count;
            this.orbitDaysMin = orbitDaysMin;
            this.orbitDaysMax = orbitDaysMax;
            this.name = name;
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    /**
     * Load and parse all territory definitions from territories.json.
     */
    public static List<TerritoryDef> load() throws IOException, JSONException {
        JSONObject root = Global.getSettings().loadJSON(CONFIG_PATH);
        JSONArray territoriesArray = root.getJSONArray("territories");

        List<TerritoryDef> territories = new ArrayList<>();
        for (int i = 0; i < territoriesArray.length(); i++) {
            territories.add(parseTerritoryDef(territoriesArray.getJSONObject(i)));
        }

        log.info("Loaded " + territories.size() + " territory definitions from " + CONFIG_PATH);
        return territories;
    }

    private static TerritoryDef parseTerritoryDef(JSONObject json) throws JSONException {
        String id = json.getString("id");
        String name = json.getString("name");

        JSONArray systemsArray = json.optJSONArray("systems");
        List<SystemDef> systems = new ArrayList<>();
        if (systemsArray != null) {
            for (int i = 0; i < systemsArray.length(); i++) {
                systems.add(parseSystemDef(systemsArray.getJSONObject(i)));
            }
        }

        return new TerritoryDef(id, name, systems);
    }

    private static SystemDef parseSystemDef(JSONObject json) throws JSONException {
        String id = json.getString("id");
        String name = json.getString("name");
        StarAge age = StarAge.valueOf(json.optString("age", "ANY"));
        String starType = json.getString("starType");
        float starRadius = (float) json.getDouble("starRadius");
        float coronaRadius = (float) json.getDouble("coronaRadius");
        int stableLocationCount = json.optInt("stableLocationCount", 0);

        List<PlanetDef> planets = new ArrayList<>();
        JSONArray planetsArray = json.optJSONArray("planets");
        if (planetsArray != null) {
            for (int i = 0; i < planetsArray.length(); i++) {
                planets.add(parsePlanetDef(planetsArray.getJSONObject(i)));
            }
        }

        List<AsteroidBeltDef> asteroidBelts = new ArrayList<>();
        JSONArray beltsArray = json.optJSONArray("asteroidBelts");
        if (beltsArray != null) {
            for (int i = 0; i < beltsArray.length(); i++) {
                asteroidBelts.add(parseAsteroidBeltDef(beltsArray.getJSONObject(i)));
            }
        }

        return new SystemDef(id, name, age, starType, starRadius, coronaRadius,
                planets, asteroidBelts, stableLocationCount);
    }

    private static PlanetDef parsePlanetDef(JSONObject json) throws JSONException {
        String id = json.getString("id");
        String name = json.getString("name");
        String type = json.getString("type");
        float orbitRadius = (float) json.getDouble("orbitRadius");
        float orbitDays = (float) json.getDouble("orbitDays");
        float orbitAngle = (float) json.optDouble("orbitAngle", 0);
        float radius = (float) json.optDouble("radius", 150);

        List<String> conditions = new ArrayList<>();
        JSONArray condArray = json.optJSONArray("conditions");
        if (condArray != null) {
            for (int i = 0; i < condArray.length(); i++) {
                conditions.add(condArray.getString(i));
            }
        }

        return new PlanetDef(id, name, type, orbitRadius, orbitDays, orbitAngle, radius, conditions);
    }

    private static AsteroidBeltDef parseAsteroidBeltDef(JSONObject json) throws JSONException {
        float orbitRadius = (float) json.getDouble("orbitRadius");
        float width = (float) json.optDouble("width", 200);
        int count = json.optInt("count", 80);
        float orbitDaysMin = (float) json.optDouble("orbitDaysMin", 100);
        float orbitDaysMax = (float) json.optDouble("orbitDaysMax", 150);
        String name = json.optString("name", null);

        return new AsteroidBeltDef(orbitRadius, width, count, orbitDaysMin, orbitDaysMax, name);
    }
}

