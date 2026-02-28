package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.Map;
import java.util.Set;

public class IntrigueListTerritoriesCommand implements BaseCommand {
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

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) {
            Console.showMessage("Territory system not available.");
            return CommandResult.ERROR;
        }

        Console.showMessage("=== Intrigue Territories ===");
        Console.showMessage("Cohesion decay per tick: " + territories.getDecayPerTick() + "\n");

        for (IntrigueTerritory t : territories.getAll()) {
            Console.showMessage(t.getName() + " [" + t.getTerritoryId() + "]"
                    + " | tier=" + t.getTier()
                    + " | constellations=" + t.getConstellationNames().size());

            if (t.getPlotHook() != null) {
                Console.showMessage("  Hook: " + t.getPlotHook());
            }

            Console.showMessage("  Constellations: " + String.join(", ", t.getConstellationNames()));

            if (!t.getInterestedFactions().isEmpty()) {
                Console.showMessage("  Interested factions: " + String.join(", ", t.getInterestedFactions()));
            }

            Set<String> activeIds = t.getActiveSubfactionIds();
            if (activeIds.isEmpty()) {
                Console.showMessage("  No subfaction presence.");
            } else {
                StringBuilder sb = new StringBuilder("  Subfaction presence:");
                for (String sfId : activeIds) {
                    IntrigueTerritory.Presence presence = t.getPresence(sfId);
                    int cohesion = t.getCohesion(sfId);
                    sb.append("\n    ").append(sfId)
                      .append(" | presence=").append(presence);
                    if (presence == IntrigueTerritory.Presence.ESTABLISHED) {
                        sb.append(" | cohesion=").append(cohesion);
                    }
                }
                Console.showMessage(sb.toString());
            }

            Console.showMessage("");
        }

        if (territories.getAll().isEmpty()) {
            Console.showMessage("No territories defined.");
        }

        return CommandResult.SUCCESS;
    }
}

