package spinloki.Intrigue.console;

import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import org.lazywizard.console.BaseCommandWithSuggestion;
import java.util.ArrayList;
import java.util.List;

public class IntrigueSetPlayerRelCommand implements BaseCommandWithSuggestion {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP) return CommandResult.WRONG_CONTEXT;

        String[] parts = args.trim().split("\\s+");
        if (parts.length != 2) {
            Console.showMessage("Usage: intrigue_set_player_rel <person> <value (-100..100)>");
            return CommandResult.BAD_SYNTAX;
        }

        String id = IntrigueArgResolver.resolvePersonIdOrNull(parts[0]);
        if (id == null) {
            Console.showMessage("Unknown person: " + parts[0] + " (try shorthand: 14, p14)");
            return CommandResult.ERROR;
        }

        int v;
        try { v = Integer.parseInt(parts[1]); }
        catch (Exception ex) { return CommandResult.BAD_SYNTAX; }

        IntriguePeopleManager.get().setRelToPlayer(id, v);
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