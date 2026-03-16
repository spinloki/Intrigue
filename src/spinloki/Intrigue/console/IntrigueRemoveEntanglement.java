package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.territory.*;

import java.util.*;

/**
 * Removes an active entanglement between two subfactions in a territory.
 * Usage: IntrigueRemoveEntanglement <territory_id> <subfaction_a> <subfaction_b>
 *
 * If no entanglement exists between the pair, reports that.
 * Use "all" as subfaction_b to remove ALL entanglements in the territory.
 *
 * Examples:
 *   IntrigueRemoveEntanglement intrigue_ashenveil intrigue_church_missionary intrigue_pirates_freehold
 *   IntrigueRemoveEntanglement intrigue_ashenveil all all
 */
public class IntrigueRemoveEntanglement implements BaseCommandWithSuggestion {

    @Override
    public List<String> getSuggestions(int argIndex, List<String> args, CommandContext context) {
        switch (argIndex) {
            case 0: {
                List<String> ids = new ArrayList<>();
                for (TerritoryManager mgr : TerritoryManager.getManagers()) {
                    ids.add(mgr.getTerritoryId());
                }
                return ids;
            }
            case 1:
            case 2: {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("all");
                if (args.size() >= 1) {
                    TerritoryManager mgr = findManager(args.get(0));
                    if (mgr != null) {
                        // Only suggest subfactions that actually have entanglements
                        Set<String> involved = new LinkedHashSet<>();
                        for (SubfactionPair pair : mgr.getState().getEntanglements().keySet()) {
                            involved.add(pair.getFirst());
                            involved.add(pair.getSecond());
                        }
                        suggestions.addAll(involved);
                    }
                }
                return suggestions;
            }
            default:
                return Collections.emptyList();
        }
    }

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP && context != CommandContext.CAMPAIGN_MARKET) {
            Console.showMessage("This command can only be used on the campaign map.");
            return CommandResult.WRONG_CONTEXT;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 3) {
            Console.showMessage("Usage: IntrigueRemoveEntanglement <territory> <subfaction_a> <subfaction_b>");
            Console.showMessage("  Use 'all all' to remove ALL entanglements in a territory.");
            return CommandResult.BAD_SYNTAX;
        }

        String territoryId = parts[0];
        String subfactionA = parts[1];
        String subfactionB = parts[2];

        TerritoryManager mgr = findManager(territoryId);
        if (mgr == null) {
            Console.showMessage("Territory '" + territoryId + "' not found.");
            return CommandResult.ERROR;
        }

        Map<SubfactionPair, ActiveEntanglement> entanglements = mgr.getState().getEntanglements();

        // Clear all entanglements
        if ("all".equalsIgnoreCase(subfactionA) || "all".equalsIgnoreCase(subfactionB)) {
            int count = entanglements.size();
            entanglements.clear();
            Console.showMessage("Removed all " + count + " entanglements from " + territoryId);
            return CommandResult.SUCCESS;
        }

        SubfactionPair pair = new SubfactionPair(subfactionA, subfactionB);
        ActiveEntanglement removed = entanglements.remove(pair);

        if (removed == null) {
            Console.showMessage("No entanglement between " + displayName(subfactionA)
                    + " and " + displayName(subfactionB) + " in " + territoryId);
            return CommandResult.ERROR;
        }

        Console.showMessage("Removed " + removed.getType() + " between "
                + displayName(subfactionA) + " and " + displayName(subfactionB)
                + " from " + territoryId);
        return CommandResult.SUCCESS;
    }

    private static TerritoryManager findManager(String territoryId) {
        for (TerritoryManager mgr : TerritoryManager.getManagers()) {
            if (mgr.getTerritoryId().equals(territoryId)) return mgr;
        }
        return null;
    }

    private static String displayName(String factionId) {
        com.fs.starfarer.api.campaign.FactionAPI f = Global.getSector().getFaction(factionId);
        return f != null ? f.getDisplayName() : factionId;
    }
}
