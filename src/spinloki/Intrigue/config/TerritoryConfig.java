package spinloki.Intrigue.config;

import java.util.*;

/**
 * Top-level container deserialized from data/config/intrigue_territories.json.
 *
 * Each TerritoryDef describes a territory template - a region of constellations
 * outside the core worlds where subfactions project power and pursue plot hooks.
 *
 * Includes game-independent parse/serialize methods for round-trip testing.
 */
public class TerritoryConfig {

    /** How much per-territory cohesion decays each pacer tick (default: 2). */
    public int territoryDecayPerTick = 2;

    public List<TerritoryDef> territories;

    public enum Tier {
        LOW,
        MEDIUM,
        HIGH
    }

    public static class TerritoryDef {
        public String territoryId;
        public String name;
        public Tier tier;
        public String plotHook;
        public int numConstellations;
        public List<String> interestedFactions;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TerritoryDef)) return false;
            TerritoryDef d = (TerritoryDef) o;
            return numConstellations == d.numConstellations
                    && Objects.equals(territoryId, d.territoryId)
                    && Objects.equals(name, d.name)
                    && tier == d.tier
                    && Objects.equals(plotHook, d.plotHook)
                    && Objects.equals(interestedFactions, d.interestedFactions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(territoryId, name, tier, plotHook, numConstellations, interestedFactions);
        }

        @Override
        public String toString() {
            return territoryId + " (" + name + ", " + tier + ")";
        }
    }

    // -- Game-independent JSON serialization --

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"territoryDecayPerTick\": ").append(territoryDecayPerTick).append(",\n");
        sb.append("  \"territories\": [\n");
        for (int i = 0; i < territories.size(); i++) {
            if (i > 0) sb.append(",\n");
            writeTerritoryDef(sb, territories.get(i), "    ");
        }
        sb.append("\n  ]\n}");
        return sb.toString();
    }

    private static void writeTerritoryDef(StringBuilder sb, TerritoryDef def, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"territoryId\": ").append(jsonStr(def.territoryId)).append(",\n");
        sb.append(indent).append("  \"name\": ").append(jsonStr(def.name)).append(",\n");
        sb.append(indent).append("  \"tier\": ").append(jsonStr(def.tier.name())).append(",\n");
        sb.append(indent).append("  \"plotHook\": ").append(jsonStr(def.plotHook)).append(",\n");
        sb.append(indent).append("  \"numConstellations\": ").append(def.numConstellations).append(",\n");
        sb.append(indent).append("  \"interestedFactions\": [");
        if (def.interestedFactions != null) {
            for (int i = 0; i < def.interestedFactions.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(jsonStr(def.interestedFactions.get(i)));
            }
        }
        sb.append("]\n");
        sb.append(indent).append("}");
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // -- Game-independent JSON parsing --

    public static TerritoryConfig parseFromJson(String json) {
        TerritoryConfig config = new TerritoryConfig();
        config.territories = new ArrayList<>();
        json = stripComments(json);

        config.territoryDecayPerTick = extractInt(json, "territoryDecayPerTick", 2);

        int idx = json.indexOf("\"territories\"");
        if (idx < 0) return config;
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return config;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');

        int pos = arrStart + 1;
        while (true) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart > arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            String objStr = json.substring(objStart, objEnd + 1);
            config.territories.add(parseTerritoryDefFromJson(objStr));
            pos = objEnd + 1;
        }
        return config;
    }

    private static TerritoryDef parseTerritoryDefFromJson(String json) {
        TerritoryDef def = new TerritoryDef();
        def.territoryId = extractString(json, "territoryId");
        def.name = extractString(json, "name");
        String tierStr = extractStringOrDefault(json, "tier", "LOW");
        try {
            def.tier = Tier.valueOf(tierStr);
        } catch (IllegalArgumentException e) {
            def.tier = Tier.LOW;
        }
        def.plotHook = extractStringOrNull(json, "plotHook");
        def.numConstellations = extractInt(json, "numConstellations", 1);
        def.interestedFactions = extractStringArray(json, "interestedFactions");
        return def;
    }

    // -- Minimal JSON helpers (same pattern as SubfactionConfig) --

    private static String stripComments(String json) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString && c == '#') {
                while (i < json.length() && json.charAt(i) != '\n') i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int findMatchingBracket(String s, int openPos, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == open) depth++;
            if (c == close) { depth--; if (depth == 0) return i; }
        }
        return s.length() - 1;
    }

    private static String extractString(String json, String key) {
        String v = extractStringOrNull(json, key);
        return v != null ? v : "";
    }

    private static String extractStringOrDefault(String json, String key, String defaultVal) {
        String v = extractStringOrNull(json, key);
        return v != null ? v : defaultVal;
    }

    private static String extractStringOrNull(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int valStart = colon + 1;
        while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
        if (valStart >= json.length()) return null;
        if (json.charAt(valStart) == 'n' && json.startsWith("null", valStart)) return null;
        if (json.charAt(valStart) != '"') return null;
        int valEnd = valStart + 1;
        while (valEnd < json.length()) {
            if (json.charAt(valEnd) == '"' && json.charAt(valEnd - 1) != '\\') break;
            valEnd++;
        }
        return json.substring(valStart + 1, valEnd);
    }

    private static int extractInt(String json, String key, int defaultVal) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultVal;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return defaultVal;
        int valStart = colon + 1;
        while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
        int valEnd = valStart;
        while (valEnd < json.length() && (Character.isDigit(json.charAt(valEnd)) || json.charAt(valEnd) == '-')) valEnd++;
        try {
            return Integer.parseInt(json.substring(valStart, valEnd));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static List<String> extractStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return result;
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return result;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        String arrContent = json.substring(arrStart + 1, arrEnd).trim();
        if (arrContent.isEmpty()) return result;
        int pos = 0;
        while (pos < arrContent.length()) {
            int qStart = arrContent.indexOf('"', pos);
            if (qStart < 0) break;
            int qEnd = qStart + 1;
            while (qEnd < arrContent.length()) {
                if (arrContent.charAt(qEnd) == '"' && arrContent.charAt(qEnd - 1) != '\\') break;
                qEnd++;
            }
            result.add(arrContent.substring(qStart + 1, qEnd));
            pos = qEnd + 1;
        }
        return result;
    }
}

