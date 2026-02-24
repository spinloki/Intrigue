package spinloki.Intrigue.console;

import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntriguePeopleManager;

public final class IntrigueArgResolver {
    private IntrigueArgResolver() {}

    /** Accepts "14", "p14", full id, or "@14"/"@fullid". Returns full id or null if not found. */
    public static String resolvePersonIdOrNull(String token) {
        if (token == null) return null;
        token = token.trim();
        if (token.isEmpty()) return null;

        // Optional prefix for readability
        if (token.startsWith("@")) token = token.substring(1);

        // numeric shorthand
        if (token.matches("\\d+")) token = IntrigueIds.PERSON_ID_PREFIX + token;

        // p14 shorthand
        if ((token.startsWith("p") || token.startsWith("P"))
                && token.length() > 1
                && token.substring(1).matches("\\d+")) {
            token = IntrigueIds.PERSON_ID_PREFIX + token.substring(1);
        }

        // Already a full id (or some other string): just validate it exists
        return (IntriguePeopleManager.get().getById(token) != null) ? token : null;
    }
}