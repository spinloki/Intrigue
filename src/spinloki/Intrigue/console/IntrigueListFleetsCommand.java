package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

import java.util.ArrayList;
import java.util.List;

public class IntrigueListFleetsCommand implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP) {
            Console.showMessage("Run this in the campaign.");
            return CommandResult.WRONG_CONTEXT;
        }

        Console.showMessage("=== Intrigue Fleets (sector-wide) ===\n");

        int found = 0;
        List<LocationAPI> locations = new ArrayList<>();
        locations.add(Global.getSector().getHyperspace());
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            locations.add(sys);
        }

        for (LocationAPI loc : locations) {
            for (CampaignFleetAPI f : loc.getFleets()) {
                if (f.getMemoryWithoutUpdate().getBoolean("$intrigueFleet")) {
                    found++;
                    String subfaction = f.getMemoryWithoutUpdate().getString("$intrigueSubfaction");
                    if (subfaction == null) subfaction = "unknown";

                    Console.showMessage(f.getName()
                            + " | " + f.getFaction().getDisplayName()
                            + " | subfaction: " + subfaction
                            + " | location: " + loc.getName()
                            + " | ships: " + f.getNumShips()
                            + " | id: " + f.getId());
                }
            }
        }

        if (found == 0) {
            Console.showMessage("No intrigue fleets currently active in the sector.");
        } else {
            Console.showMessage("\nTotal: " + found + " fleet(s)");
        }

        return CommandResult.SUCCESS;
    }
}