package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePerson;

import java.util.*;

public class IntrigueHealthCommand implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        boolean verbose = args != null && args.toLowerCase(Locale.ROOT).contains("verbose");

        IntriguePeopleManager mgr = IntriguePeopleManager.get();

        Map<String, MarketAPI> marketsById = new HashMap<>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m != null) marketsById.put(m.getId(), m);
        }

        int total = 0;
        int missingImportant = 0;

        int missingHomeMarket = 0;

        // When NOT checked out, these are problems:
        int missingFromHomeMarketPeople = 0;
        int missingFromHomeComm = 0;
        int homeMarketDupesExtra = 0;
        int homeCommDupesExtra = 0;
        int homeCommNull = 0;

        // When checked out, these are problems (they should not be at home):
        int leakedIntoHomeMarketPeople = 0;
        int leakedIntoHomeComm = 0;
        int leakedHomeMarketDupesExtra = 0;
        int leakedHomeCommDupesExtra = 0;

        int checkedOutCount = 0;
        Map<String, Integer> checkedOutByType = new TreeMap<>();

        Map<String, Integer> perFaction = new TreeMap<>();
        List<String> problems = new ArrayList<>();

        for (IntriguePerson ip : mgr.getAll()) {
            total++;
            perFaction.merge(ip.getFactionId(), 1, Integer::sum);

            String id = ip.getPersonId();
            PersonAPI p = Global.getSector().getImportantPeople().getPerson(id);
            if (p == null) {
                missingImportant++;
            }

            boolean checkedOut = ip.isCheckedOut();
            if (checkedOut) {
                checkedOutCount++;
                String t = String.valueOf(ip.getLocationType());
                checkedOutByType.merge(t, 1, Integer::sum);
            }

            String homeId = ip.getHomeMarketId();
            MarketAPI home = marketsById.get(homeId);

            boolean bad = false;
            StringBuilder sb = new StringBuilder();
            sb.append(id)
                    .append(" [")
                    .append(checkedOut ? "OUT " + ip.getLocationType() + ":" + ip.getLocationId() : "HOME")
                    .append("]")
                    .append(" home=")
                    .append(homeId)
                    .append(": ");

            if (p == null) {
                bad = true;
                sb.append("[missing ImportantPeople] ");
            }

            if (home == null) {
                missingHomeMarket++;
                bad = true;
                sb.append("[missing home market] ");
                if (bad) problems.add(sb.toString());
                continue;
            }

            // Count presence by ID in home market people
            int inHomeMarket = 0;
            for (PersonAPI mp : home.getPeopleCopy()) {
                if (mp != null && id.equals(mp.getId())) inHomeMarket++;
            }

            // Count presence by ID in home comm directory
            CommDirectoryAPI comm = home.getCommDirectory();
            int inHomeComm = 0;
            if (comm != null) {
                for (CommDirectoryEntryAPI e : comm.getEntriesCopy()) {
                    Object data = e.getEntryData();
                    if (data instanceof PersonAPI) {
                        PersonAPI cp = (PersonAPI) data;
                        if (id.equals(cp.getId())) inHomeComm++;
                    }
                }
            }

            if (!checkedOut) {
                // Expected to be at home
                if (inHomeMarket == 0) {
                    missingFromHomeMarketPeople++;
                    bad = true;
                    sb.append("[not in home market people] ");
                } else if (inHomeMarket > 1) {
                    homeMarketDupesExtra += (inHomeMarket - 1);
                    bad = true;
                    sb.append("[home market dupes=").append(inHomeMarket).append("] ");
                }

                if (comm == null) {
                    homeCommNull++;
                    if (verbose) {
                        bad = true;
                        sb.append("[home comm null] ");
                    }
                } else {
                    if (inHomeComm == 0) {
                        missingFromHomeComm++;
                        bad = true;
                        sb.append("[not in home comm] ");
                    } else if (inHomeComm > 1) {
                        homeCommDupesExtra += (inHomeComm - 1);
                        bad = true;
                        sb.append("[home comm dupes=").append(inHomeComm).append("] ");
                    }
                }
            } else {
                // Checked out: should NOT be present at home (if your refreshAll removes them)
                if (inHomeMarket > 0) {
                    leakedIntoHomeMarketPeople++;
                    bad = true;
                    sb.append("[LEAK in home market people=").append(inHomeMarket).append("] ");
                    if (inHomeMarket > 1) leakedHomeMarketDupesExtra += (inHomeMarket - 1);
                }
                if (comm != null && inHomeComm > 0) {
                    leakedIntoHomeComm++;
                    bad = true;
                    sb.append("[LEAK in home comm=").append(inHomeComm).append("] ");
                    if (inHomeComm > 1) leakedHomeCommDupesExtra += (inHomeComm - 1);
                }
            }

            if (bad) problems.add(sb.toString());
        }

        Console.showMessage("=== Intrigue Health ===");
        Console.showMessage("Total: " + total);
        Console.showMessage("Per-faction: " + perFaction);
        Console.showMessage("Checked out: " + checkedOutCount + " " + checkedOutByType);

        Console.showMessage("Missing ImportantPeople: " + missingImportant);
        Console.showMessage("Missing home market: " + missingHomeMarket);

        Console.showMessage("--- Home expectations (not checked out) ---");
        Console.showMessage("Not in home market people: " + missingFromHomeMarketPeople);
        Console.showMessage("Home market duplicate entries (extra): " + homeMarketDupesExtra);
        Console.showMessage("Home comm null: " + homeCommNull);
        Console.showMessage("Not in home comm: " + missingFromHomeComm);
        Console.showMessage("Home comm duplicate entries (extra): " + homeCommDupesExtra);

        Console.showMessage("--- Checkout leaks (checked out) ---");
        Console.showMessage("Leaked into home market people: " + leakedIntoHomeMarketPeople);
        Console.showMessage("Leaked home market dupes (extra): " + leakedHomeMarketDupesExtra);
        Console.showMessage("Leaked into home comm: " + leakedIntoHomeComm);
        Console.showMessage("Leaked home comm dupes (extra): " + leakedHomeCommDupesExtra);

        if (!problems.isEmpty()) {
            Console.showMessage("--- Problems ---");
            for (String s : problems) Console.showMessage(s);
        } else {
            Console.showMessage("No problems detected.");
        }

        return CommandResult.SUCCESS;
    }
}