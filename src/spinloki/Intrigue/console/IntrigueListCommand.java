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
        if (!IntrigueCommandUtil.isCampaignContext(context)) {
            Console.showMessage("Run this in the campaign.");
            return CommandResult.WRONG_CONTEXT;
        }

        Console.showMessage("Intrigue people:");
        for (IntriguePerson ip : IntriguePeopleManager.get().getAll()) {
            MarketAPI m = null;
            for (MarketAPI mk : Global.getSector().getEconomy().getMarketsCopy()) {
                if (mk != null && mk.getId().equals(ip.getHomeMarketId())) { m = mk; break; }
            }

            var name = ip.resolvePerson().getName().getFullName();
            String marketName = (m != null) ? m.getName() : ip.getHomeMarketId();
            Console.showMessage(" - " + ip.getPersonId() + " | " + name + " | " + ip.getFactionId() + " | " + marketName);
        }

        return CommandResult.SUCCESS;
    }
}