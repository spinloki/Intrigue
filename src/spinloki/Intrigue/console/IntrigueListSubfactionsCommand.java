package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

public class IntrigueListSubfactionsCommand implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) {
            Console.showMessage("Run this in the campaign.");
            return CommandResult.WRONG_CONTEXT;
        }

        if (!IntrigueServices.isInitialized()) {
            Console.showMessage("IntrigueServices not initialized.");
            return CommandResult.ERROR;
        }

        Console.showMessage("=== Intrigue Subfactions ===\n");

        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            MarketAPI market = Global.getSector().getEconomy().getMarket(sf.getHomeMarketId());
            String marketName = market != null ? market.getName() : sf.getHomeMarketId();

            Console.showMessage(sf.getName() + " [" + sf.getSubfactionId() + "]"
                    + " | " + sf.getFactionId()
                    + " | " + marketName
                    + " | power=" + sf.getPower()
                    + " | type=" + sf.getType()
                    + (sf.isHidden() ? " | HIDDEN" : ""));

            // Leader
            String leaderId = sf.getLeaderId();
            if (leaderId != null) {
                IntriguePerson leaderIp = IntrigueServices.people().getById(leaderId);
                PersonAPI leaderP = Global.getSector().getImportantPeople().getPerson(leaderId);
                String leaderName = leaderP != null ? leaderP.getName().getFullName() : leaderId;
                String traits = leaderIp != null ? String.join(",", leaderIp.getTraits()) : "";
                Console.showMessage("  LEADER: " + leaderName + " [" + leaderId + "]"
                        + (traits.isEmpty() ? "" : " traits=" + traits));
            }

            // Members
            for (String memberId : sf.getMemberIds()) {
                IntriguePerson memberIp = IntrigueServices.people().getById(memberId);
                PersonAPI memberP = Global.getSector().getImportantPeople().getPerson(memberId);
                String memberName = memberP != null ? memberP.getName().getFullName() : memberId;
                String bonus = memberIp != null && memberIp.getBonus() != null ? memberIp.getBonus() : "none";
                Console.showMessage("  MEMBER: " + memberName + " [" + memberId + "]"
                        + " bonus=\"" + bonus + "\"");
            }

            // Relationships
            if (!sf.getRelToOthersView().isEmpty()) {
                StringBuilder rels = new StringBuilder("  Rels: ");
                for (var entry : sf.getRelToOthersView().entrySet()) {
                    rels.append(entry.getKey()).append("=").append(entry.getValue()).append(" ");
                }
                Console.showMessage(rels.toString().trim());
            }

            Console.showMessage("");
        }

        return CommandResult.SUCCESS;
    }
}
