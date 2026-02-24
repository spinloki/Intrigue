package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;

public class IntrigueInitCommand implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) {
            Console.showMessage("Run this in the campaign.");
            return CommandResult.WRONG_CONTEXT;
        }

        IntriguePeopleManager.get().bootstrapIfNeeded();
        IntriguePeopleManager.get().refreshAll();
        Console.showMessage("Intrigue: people initialized/refreshed.");
        return CommandResult.SUCCESS;
    }
}