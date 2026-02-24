package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import java.util.ArrayList;
import java.util.List;

public class IntrigueSetRelCommand implements BaseCommandWithSuggestion {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        String[] parts = args.trim().split("\\s+");
        if (parts.length != 3) {
            Console.showMessage("Usage: intrigue_set_rel <personA> <personB> <value (-100..100)>");
            return CommandResult.BAD_SYNTAX;
        }

        String a = IntrigueCommandUtil.resolvePersonIdOrNull(parts[0]);
        String b = IntrigueCommandUtil.resolvePersonIdOrNull(parts[1]);
        if (a == null || b == null) {
            Console.showMessage("Could not resolve: " + parts[0] + " or " + parts[1]);
            return CommandResult.ERROR;
        }

        int v;
        try { v = Integer.parseInt(parts[2]); }
        catch (Exception ex) { return CommandResult.BAD_SYNTAX; }

        IntriguePeopleManager.get().setRelationship(a, b, v);
        Console.showMessage("Set relationship " + a + " <-> " + b + " to " + v);
        IntriguePeopleManager.get().syncMemory(a);
        IntriguePeopleManager.get().syncMemory(b);
        return CommandResult.SUCCESS;
    }

    @Override
    public List<String> getSuggestions(int parameter, List<String> previous, CommandContext context) {
        if (parameter == 0) {
            return IntrigueSuggestions.suggestPersonIds(parameter, previous, context);
        } else if (parameter == 1) {
            return IntrigueSuggestions.suggestRelValues(context);
        }
        return new ArrayList<>();
    }
}