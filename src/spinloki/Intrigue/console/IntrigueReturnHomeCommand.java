package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;

import java.util.ArrayList;
import java.util.List;

public class IntrigueReturnHomeCommand implements BaseCommandWithSuggestion {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        String[] parts = args.trim().split("\\s+");
        if (parts.length != 1) {
            Console.showMessage("Usage: intrigue_return_home <person>");
            return CommandResult.BAD_SYNTAX;
        }

        String id = IntrigueCommandUtil.resolvePersonIdOrNull(parts[0]);
        if (id == null) {
            Console.showMessage("Unknown person: " + parts[0] + " (try 14, p14, @14)");
            return CommandResult.ERROR;
        }

        IntriguePeopleManager.get().returnHome(id);
        Console.showMessage("Returned " + id + " to home.");
        return CommandResult.SUCCESS;
    }

    @Override
    public List<String> getSuggestions(int parameter, List<String> previous, CommandContext context) {
        if (parameter == 0) {
            return IntrigueSuggestions.suggestPersonIds(parameter, previous, context);
        }
        return new ArrayList<>();
    }
}