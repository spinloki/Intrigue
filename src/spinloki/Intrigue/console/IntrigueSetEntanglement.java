package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;
import spinloki.Intrigue.territory.*;

import java.util.*;

/**
 * Injects an entanglement between two subfactions in a territory.
 * Usage: IntrigueSetEntanglement <territory_id> <subfaction_a> <subfaction_b> <type> [duration_days] [third_party]
 *
 * Examples:
 *   IntrigueSetEntanglement intrigue_ashenveil intrigue_church_missionary intrigue_pirates_freehold TERRITORIAL_WAR
 *   IntrigueSetEntanglement intrigue_ashenveil intrigue_church_missionary intrigue_pirates_freehold HIRED_PROTECTION 90
 *   IntrigueSetEntanglement intrigue_cindervast intrigue_hegemony_expeditionary intrigue_tritachyon_ventures PROXY_SUPPORT 60 intrigue_path_zealots
 *
 * Duration defaults: condition-based (-1) for TERRITORIAL_WAR/CIVIL_WAR, 90 days for others.
 */
public class IntrigueSetEntanglement implements BaseCommandWithSuggestion {

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getSuggestions(int argIndex, List<String> args, CommandContext context) {
        switch (argIndex) {
            case 0: {
                // Territory IDs
                List<String> ids = new ArrayList<>();
                for (TerritoryManager mgr : TerritoryManager.getManagers()) {
                    ids.add(mgr.getTerritoryId());
                }
                return ids;
            }
            case 1:
            case 2:
            case 5: {
                // Subfaction IDs (filtered to territory if possible)
                if (args.size() >= 1) {
                    TerritoryManager mgr = findManager(args.get(0));
                    if (mgr != null) {
                        return new ArrayList<>(mgr.getPresences().keySet());
                    }
                }
                // Fallback: all subfactions
                List<SubfactionDef> defs = (List<SubfactionDef>)
                        Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
                if (defs != null) {
                    List<String> ids = new ArrayList<>();
                    for (SubfactionDef def : defs) ids.add(def.id);
                    return ids;
                }
                return Collections.emptyList();
            }
            case 3: {
                // Entanglement types
                List<String> types = new ArrayList<>();
                for (EntanglementType t : EntanglementType.values()) types.add(t.name());
                return types;
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
        if (parts.length < 4) {
            printUsage();
            return CommandResult.BAD_SYNTAX;
        }

        String territoryId = parts[0];
        String subfactionA = parts[1];
        String subfactionB = parts[2];
        String typeName = parts[3];

        // Parse entanglement type
        EntanglementType type;
        try {
            type = EntanglementType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            Console.showMessage("Unknown entanglement type: " + typeName);
            Console.showMessage("Valid types: " + Arrays.toString(EntanglementType.values()));
            return CommandResult.ERROR;
        }

        // Find territory
        TerritoryManager mgr = findManager(territoryId);
        if (mgr == null) {
            Console.showMessage("Territory '" + territoryId + "' not found.");
            List<String> available = new ArrayList<>();
            for (TerritoryManager m : TerritoryManager.getManagers()) available.add(m.getTerritoryId());
            Console.showMessage("Available: " + available);
            return CommandResult.ERROR;
        }

        // Validate subfactions are present in territory
        TerritoryState state = mgr.getState();
        if (state.getPresence(subfactionA) == null) {
            Console.showMessage("Subfaction '" + subfactionA + "' not present in " + territoryId);
            return CommandResult.ERROR;
        }
        if (state.getPresence(subfactionB) == null) {
            Console.showMessage("Subfaction '" + subfactionB + "' not present in " + territoryId);
            return CommandResult.ERROR;
        }
        if (subfactionA.equals(subfactionB)) {
            Console.showMessage("Subfaction A and B must be different.");
            return CommandResult.ERROR;
        }

        // Parse optional duration
        float duration;
        if (parts.length >= 5) {
            try {
                duration = Float.parseFloat(parts[4]);
            } catch (NumberFormatException e) {
                Console.showMessage("Invalid duration: " + parts[4]);
                return CommandResult.ERROR;
            }
        } else {
            // Default: condition-based for war types, 90 days for others
            duration = (type == EntanglementType.TERRITORIAL_WAR || type == EntanglementType.CIVIL_WAR)
                    ? -1f : 90f;
        }

        // Parse optional third party
        String thirdParty = null;
        if (parts.length >= 6) {
            thirdParty = parts[5];
            if (state.getPresence(thirdParty) == null) {
                Console.showMessage("Third party '" + thirdParty + "' not present in " + territoryId);
                return CommandResult.ERROR;
            }
        }

        // Create and apply
        SubfactionPair pair = new SubfactionPair(subfactionA, subfactionB);
        ActiveEntanglement entanglement = new ActiveEntanglement(
                type, pair, duration, thirdParty,
                "Console command: IntrigueSetEntanglement");

        TerritoryState.TickResult result = state.applyEntanglement(entanglement);

        // Report
        String nameA = displayName(subfactionA);
        String nameB = displayName(subfactionB);
        String durStr = duration < 0 ? "condition-based" : (int) duration + " days";

        if (result.type == TerritoryState.TickResultType.ENTANGLEMENT_CREATED) {
            Console.showMessage("Created " + type + " between " + nameA + " and " + nameB
                    + " in " + territoryId + " (" + durStr + ")");
        } else {
            Console.showMessage("Replaced existing entanglement with " + type
                    + " between " + nameA + " and " + nameB
                    + " in " + territoryId + " (" + durStr + ")");
        }
        if (thirdParty != null) {
            Console.showMessage("  Third party: " + displayName(thirdParty));
        }
        if (type.setsHostile) {
            Console.showMessage("  Effect: SETS HOSTILE");
        } else if (type.suppressesHostile) {
            Console.showMessage("  Effect: SUPPRESSES HOSTILITY");
        }

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

    private static void printUsage() {
        Console.showMessage("Usage: IntrigueSetEntanglement <territory> <subfaction_a> <subfaction_b> <type> [duration] [third_party]");
        Console.showMessage("Types: " + Arrays.toString(EntanglementType.values()));
        Console.showMessage("Examples:");
        Console.showMessage("  IntrigueSetEntanglement intrigue_ashenveil intrigue_church_missionary intrigue_pirates_freehold TERRITORIAL_WAR");
        Console.showMessage("  IntrigueSetEntanglement intrigue_ashenveil intrigue_church_missionary intrigue_pirates_freehold HIRED_PROTECTION 90");
        Console.showMessage("  IntrigueSetEntanglement intrigue_cindervast intrigue_hegemony_expeditionary intrigue_tritachyon_ventures PROXY_SUPPORT 60 intrigue_path_zealots");
    }
}
