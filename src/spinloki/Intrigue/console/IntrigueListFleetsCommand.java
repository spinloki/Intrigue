package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

import java.util.List;

public class IntrigueListFleetsCommand implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP) {
            Console.showMessage("Run this in the campaign.");
            return CommandResult.WRONG_CONTEXT;
        }

        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        Console.showMessage("Player fleet: " + player.getName() + " id=" + player.getId());

        LocationAPI loc = Global.getSector().getCurrentLocation();
        List<CampaignFleetAPI> fleets = loc.getEntities(CampaignFleetAPI.class);

        Console.showMessage("Fleets in current location (" + loc.getName() + "):");
        for (CampaignFleetAPI f : fleets) {
            // this will include the player fleet too, which is fine
            Console.showMessage(" - " + f.getName() + " [" + f.getFaction().getId() + "] id=" + f.getId());
        }

        return CommandResult.SUCCESS;
    }
}