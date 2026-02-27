package spinloki.Intrigue.config;
import java.util.*;
/**
 * Top-level container deserialized from data/config/intrigue_subfactions.json.
 *
 * Each SubfactionDef describes one subfaction with its leader and members.
 * The mod creates PersonAPI + IntriguePerson + IntrigueSubfaction instances
 * from these definitions at bootstrap time.
 *
 * Includes game-independent parse/serialize methods for round-trip testing.
 */
public class SubfactionConfig {
    public List<SubfactionDef> subfactions;
    public static class SubfactionDef {
        public String subfactionId;
        public String name;
        public String factionId;
        public String homeMarketId;
        public String type; // "POLITICAL" or "CRIMINAL"; defaults to "POLITICAL"
        public int power;
        public List<MemberDef> members;
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubfactionDef)) return false;
            SubfactionDef d = (SubfactionDef) o;
            return power == d.power
                    && Objects.equals(subfactionId, d.subfactionId)
                    && Objects.equals(name, d.name)
                    && Objects.equals(factionId, d.factionId)
                    && Objects.equals(homeMarketId, d.homeMarketId)
                    && Objects.equals(type, d.type)
                    && Objects.equals(members, d.members);
        }
        @Override
        public int hashCode() {
            return Objects.hash(subfactionId, name, factionId, homeMarketId, type, power, members);
        }
        @Override
        public String toString() {
            return subfactionId + " (" + name + ")";
        }
    }
    public static class MemberDef {
        public String role;
        public String firstName;
        public String lastName;
        public String gender;
        public String portraitId;
        public String rankId;
        public String postId;
        public String bonus;
        public List<String> traits;
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemberDef)) return false;
            MemberDef m = (MemberDef) o;
            return Objects.equals(role, m.role)
                    && Objects.equals(firstName, m.firstName)
                    && Objects.equals(lastName, m.lastName)
                    && Objects.equals(gender, m.gender)
                    && Objects.equals(portraitId, m.portraitId)
                    && Objects.equals(rankId, m.rankId)
                    && Objects.equals(postId, m.postId)
                    && Objects.equals(bonus, m.bonus)
                    && Objects.equals(traits, m.traits);
        }
        @Override
        public int hashCode() {
            return Objects.hash(role, firstName, lastName, gender, portraitId, rankId, postId, bonus, traits);
        }
        @Override
        public String toString() {
            return role + ": " + firstName + " " + lastName;
        }
    }
    // -- Game-independent JSON serialization --
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"subfactions\": [\n");
        for (int i = 0; i < subfactions.size(); i++) {
            if (i > 0) sb.append(",\n");
            writeSubfactionDef(sb, subfactions.get(i), "    ");
        }
        sb.append("\n  ]\n}");
        return sb.toString();
    }
    private static void writeSubfactionDef(StringBuilder sb, SubfactionDef def, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"subfactionId\": ").append(jsonStr(def.subfactionId)).append(",\n");
        sb.append(indent).append("  \"name\": ").append(jsonStr(def.name)).append(",\n");
        sb.append(indent).append("  \"factionId\": ").append(jsonStr(def.factionId)).append(",\n");
        if (def.homeMarketId != null) {
            sb.append(indent).append("  \"homeMarketId\": ").append(jsonStr(def.homeMarketId)).append(",\n");
        }
        if (def.type != null && !"POLITICAL".equals(def.type)) {
            sb.append(indent).append("  \"type\": ").append(jsonStr(def.type)).append(",\n");
        }
        sb.append(indent).append("  \"power\": ").append(def.power).append(",\n");
        sb.append(indent).append("  \"members\": [\n");
        for (int i = 0; i < def.members.size(); i++) {
            if (i > 0) sb.append(",\n");
            writeMemberDef(sb, def.members.get(i), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }
    private static void writeMemberDef(StringBuilder sb, MemberDef m, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"role\": ").append(jsonStr(m.role)).append(",\n");
        sb.append(indent).append("  \"firstName\": ").append(jsonStr(m.firstName)).append(",\n");
        sb.append(indent).append("  \"lastName\": ").append(jsonStr(m.lastName)).append(",\n");
        sb.append(indent).append("  \"gender\": ").append(jsonStr(m.gender)).append(",\n");
        sb.append(indent).append("  \"portraitId\": ").append(jsonStr(m.portraitId)).append(",\n");
        sb.append(indent).append("  \"rankId\": ").append(jsonStr(m.rankId)).append(",\n");
        sb.append(indent).append("  \"postId\": ").append(jsonStr(m.postId)).append(",\n");
        if (m.bonus != null) {
            sb.append(indent).append("  \"bonus\": ").append(jsonStr(m.bonus)).append(",\n");
        }
        sb.append(indent).append("  \"traits\": [");
        for (int i = 0; i < m.traits.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jsonStr(m.traits.get(i)));
        }
        sb.append("]\n");
        sb.append(indent).append("}");
    }
    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    // -- Game-independent JSON parsing --
    public static SubfactionConfig parseFromJson(String json) {
        SubfactionConfig config = new SubfactionConfig();
        config.subfactions = new ArrayList<>();
        json = stripComments(json);
        int idx = json.indexOf("\"subfactions\"");
        if (idx < 0) return config;
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return config;
        int pos = arrStart + 1;
        while (true) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
            if (objStart > arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            String objStr = json.substring(objStart, objEnd + 1);
            config.subfactions.add(parseSubfactionDefFromJson(objStr));
            pos = objEnd + 1;
        }
        return config;
    }
    private static SubfactionDef parseSubfactionDefFromJson(String json) {
        SubfactionDef def = new SubfactionDef();
        def.subfactionId = extractString(json, "subfactionId");
        def.name = extractString(json, "name");
        def.factionId = extractString(json, "factionId");
        def.homeMarketId = extractStringOrNull(json, "homeMarketId");
        def.type = extractStringOrDefault(json, "type", "POLITICAL");
        def.power = extractInt(json, "power", 50);
        def.members = new ArrayList<>();
        int idx = json.indexOf("\"members\"");
        if (idx >= 0) {
            int arrStart = json.indexOf('[', idx);
            if (arrStart >= 0) {
                int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
                String arrStr = json.substring(arrStart + 1, arrEnd);
                int pos = 0;
                while (true) {
                    int objStart = arrStr.indexOf('{', pos);
                    if (objStart < 0) break;
                    int objEnd = findMatchingBracket(arrStr, objStart, '{', '}');
                    String memberStr = arrStr.substring(objStart, objEnd + 1);
                    def.members.add(parseMemberDefFromJson(memberStr));
                    pos = objEnd + 1;
                }
            }
        }
        return def;
    }
    private static MemberDef parseMemberDefFromJson(String json) {
        MemberDef m = new MemberDef();
        m.role = extractString(json, "role");
        m.firstName = extractString(json, "firstName");
        m.lastName = extractString(json, "lastName");
        m.gender = extractStringOrDefault(json, "gender", "MALE");
        m.portraitId = extractStringOrNull(json, "portraitId");
        m.rankId = extractStringOrNull(json, "rankId");
        m.postId = extractStringOrNull(json, "postId");
        m.bonus = extractStringOrNull(json, "bonus");
        m.traits = extractStringArray(json, "traits");
        return m;
    }
    // -- Minimal JSON helpers --
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
