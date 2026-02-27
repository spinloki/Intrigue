package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueSubfactionManager;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.List;

/**
 * Console command: intrigue_homeless_log
 *
 * Prints the rolling audit log of homelessness-check events
 * (bootstrap assignments + periodic resolution attempts).
 * Useful for verifying that pirates, pathers, etc. eventually find a home market.
 *
 * Optional argument: "status" — also prints the current homeless/homed status of every subfaction.
 */
public class IntrigueHomelessLogCommand implements BaseCommand {
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

        boolean showStatus = args != null && args.toLowerCase().contains("status");

        IntrigueSubfactionManager mgr = IntrigueSubfactionManager.get();

        // Current status summary
        if (showStatus) {
            Console.showMessage("=== Current Subfaction Home Status ===");
            for (IntrigueSubfaction sf : mgr.getAll()) {
                String home = sf.hasHomeMarket()
                        ? sf.getHomeMarketId()
                        : "HOMELESS";
                Console.showMessage("  " + sf.getName()
                        + " [" + sf.getSubfactionId() + "]"
                        + " faction=" + sf.getFactionId()
                        + " home=" + home);
            }
            Console.showMessage("");
        }

        // Audit log
        List<String> log = mgr.getHomelessLog();
        if (log.isEmpty()) {
            Console.showMessage("=== Homeless Log: (empty — no events recorded yet) ===");
        } else {
            Console.showMessage("=== Homeless Log (" + log.size() + " entries) ===");
            for (String entry : log) {
                Console.showMessage("  " + entry);
            }
        }

        return CommandResult.SUCCESS;
    }
}

