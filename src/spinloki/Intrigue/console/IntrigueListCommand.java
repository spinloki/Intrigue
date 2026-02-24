package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePerson;

public class IntrigueListCommand implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP && context != CommandContext.CAMPAIGN_MARKET) {
            Console.showMessage("Run this in the campaign.");
            return CommandResult.WRONG_CONTEXT;
        }

        Console.showMessage("Intrigue people:");
        for (IntriguePerson ip : IntriguePeopleManager.get().getAll()) {
            MarketAPI m = null;
            for (MarketAPI mk : Global.getSector().getEconomy().getMarketsCopy()) {
                if (mk != null && mk.getId().equals(ip.getMarketId())) { m = mk; break; }
            }

            var name = ip.resolvePerson().getName().getFullName();
            String marketName = (m != null) ? m.getName() : ip.getMarketId();
            Console.showMessage(" - " + ip.getPersonId() + " | " + name + " | " + ip.getFactionId() + " | " + marketName);
        }

        return CommandResult.SUCCESS;
    }
}