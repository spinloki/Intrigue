package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IntrigueCheckoutCommand implements BaseCommandWithSuggestion {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            Console.showMessage("Usage: intrigue_checkout <person> fleet [fleetId]");
            Console.showMessage("   or: intrigue_checkout <person> market <marketId>");
            return CommandResult.BAD_SYNTAX;
        }

        String id = IntrigueCommandUtil.resolvePersonIdOrNull(parts[0]);
        if (id == null) {
            Console.showMessage("Unknown person: " + parts[0] + " (try 14, p14, @14)");
            return CommandResult.ERROR;
        }

        String mode = parts[1].toLowerCase(Locale.ROOT);

        if ("fleet".equals(mode)) {
            String fleetId;
            if (parts.length >= 3) {
                fleetId = parts[2];
            } else {
                if (Global.getSector().getPlayerFleet() == null) {
                    Console.showMessage("No player fleet found to default to.");
                    return CommandResult.ERROR;
                }
                fleetId = Global.getSector().getPlayerFleet().getId();
            }

            IntriguePeopleManager.get().checkoutToFleet(id, fleetId);
            Console.showMessage("Checked out " + id + " to fleet: " + fleetId);
            return CommandResult.SUCCESS;
        }

        if ("market".equals(mode)) {
            if (parts.length < 3) {
                Console.showMessage("Usage: intrigue_checkout <person> market <marketId>");
                return CommandResult.BAD_SYNTAX;
            }
            String marketId = parts[2];
            IntriguePeopleManager.get().checkoutToMarket(id, marketId);
            Console.showMessage("Checked out " + id + " to market: " + marketId);
            return CommandResult.SUCCESS;
        }

        Console.showMessage("Unknown mode: " + mode + " (use: fleet, market, unknown)");
        return CommandResult.BAD_SYNTAX;
    }

    @Override
    public List<String> getSuggestions(int parameter, List<String> previous, CommandContext context) {
        if (parameter == 0) {
            return IntrigueSuggestions.suggestPersonIds(parameter, previous, context);
        }
        if (parameter == 1) {
            return List.of("fleet", "market", "unknown");
        }
        return new ArrayList<>();
    }
}