package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePerson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class IntrigueShowCommand implements BaseCommandWithSuggestion {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP) return CommandResult.WRONG_CONTEXT;

        String id = IntrigueArgResolver.resolvePersonIdOrNull(args);
        if (id == null) {
            Console.showMessage("Usage: intrigue_show <personId>  (you can use shorthand: 14, p14, @14)");
            return CommandResult.BAD_SYNTAX;
        }

        IntriguePerson ip = IntriguePeopleManager.get().getById(id);
        if (ip == null) {
            Console.showMessage("No Intrigue person with id: " + id);
            return CommandResult.ERROR;
        }

        Console.showMessage("ID: " + ip.getPersonId());
        Console.showMessage("Faction: " + ip.getFactionId());
        Console.showMessage("Market: " + ip.getMarketId());
        Console.showMessage("Power: " + ip.getPower());
        Console.showMessage("RelToPlayer: " + ip.getRelToPlayer());
        Console.showMessage("Traits: " + ip.getTraits());

        Console.showMessage("RelToOthers:");
        for (Map.Entry<String, Integer> e : ip.getRelToOthersView().entrySet()) {
            Console.showMessage("  - " + e.getKey() + " = " + e.getValue());
        }

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