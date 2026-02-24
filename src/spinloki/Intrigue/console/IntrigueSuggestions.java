package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommand;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePerson;

import java.util.*;
import java.util.stream.Collectors;

public final class IntrigueSuggestions {
    private IntrigueSuggestions() {}

    /**
     * Suggest intrigue person ids for the argument at index {@code parameter}.
     * Supports both full ids and numeric shorthand (if the id matches PERSON_ID_PREFIX + number).
     */
    public static List<String> suggestPersonIds(int parameter, List<String> previous, BaseCommand.CommandContext context) {
        if (!context.isInCampaign()) return Collections.emptyList();

        String typed = getTypedToken(parameter, previous).toLowerCase(Locale.ROOT);

        List<String> fullIds = IntriguePeopleManager.get().getAll().stream()
                .map(IntriguePerson::getPersonId)
                .sorted()
                .collect(Collectors.toList());

        // Build suggestions: numeric shorthand first, then full ids.
        // Example: "intrigue_person_14" -> "14"
        List<String> shorts = fullIds.stream()
                .map(IntrigueSuggestions::toShortId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingInt(IntrigueSuggestions::safeParseInt))
                .collect(Collectors.toList());

        List<String> combined = new ArrayList<>(shorts.size() + fullIds.size());
        combined.addAll(shorts);
        combined.addAll(fullIds);

        if (typed.isEmpty()) return combined;

        return combined.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).contains(typed))
                .collect(Collectors.toList());
    }

    public static List<String> suggestRelValues(BaseCommand.CommandContext context) {
        if (!context.isInCampaign()) return Collections.emptyList();
        return List.of("-100", "-75", "-50", "-25", "0", "25", "50", "75", "100");
    }

    private static String getTypedToken(int parameter, List<String> previous) {
        // Console Commands passes prior tokens; sometimes includes the current partial token, sometimes not.
        // This is safe either way.
        if (previous == null) return "";
        if (previous.size() <= parameter) return "";
        String t = previous.get(parameter);
        return t == null ? "" : t;
    }

    private static String toShortId(String fullId) {
        if (fullId == null) return null;
        if (!fullId.startsWith(IntrigueIds.PERSON_ID_PREFIX)) return null;
        String rest = fullId.substring(IntrigueIds.PERSON_ID_PREFIX.length());
        return rest.matches("\\d+") ? rest : null;
    }

    private static int safeParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return Integer.MAX_VALUE; }
    }

    public static String resolvePersonIdOrNull(String token) {
        if (token == null) return null;
        token = token.trim();
        if (token.isEmpty()) return null;

        // numeric shorthand
        if (token.matches("\\d+")) token = IntrigueIds.PERSON_ID_PREFIX + token;
        // p14 shorthand
        if ((token.startsWith("p") || token.startsWith("P")) && token.substring(1).matches("\\d+")) {
            token = IntrigueIds.PERSON_ID_PREFIX + token.substring(1);
        }

        if (IntriguePeopleManager.get().getById(token) != null) return token;
        return null;
    }
}