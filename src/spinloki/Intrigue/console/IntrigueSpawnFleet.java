package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spawns a patrol fleet of the specified subfaction near the player.
 * Usage: IntrigueSpawnFleet <subfaction_id>
 * Example: IntrigueSpawnFleet intrigue_hegemony_expeditionary
 */
public class IntrigueSpawnFleet implements BaseCommandWithSuggestion {

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getSuggestions(int argIndex, List<String> args, CommandContext context) {
        if (argIndex == 0) {
            List<SubfactionDef> defs = (List<SubfactionDef>)
                    Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
            if (defs != null) {
                List<String> ids = new ArrayList<>();
                for (SubfactionDef def : defs) {
                    ids.add(def.id);
                }
                return ids;
            }
        }
        return Collections.emptyList();
    }

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP && context != CommandContext.CAMPAIGN_MARKET) {
            Console.showMessage("This command can only be used on the campaign map.");
            return CommandResult.WRONG_CONTEXT;
        }

        String factionId = args.trim();
        if (factionId.isEmpty()) {
            Console.showMessage("Usage: IntrigueSpawnFleet <subfaction_id>");
            Console.showMessage("Example: IntrigueSpawnFleet intrigue_hegemony_expeditionary");
            return CommandResult.BAD_SYNTAX;
        }

        FactionAPI faction = Global.getSector().getFaction(factionId);
        if (faction == null) {
            Console.showMessage("Faction '" + factionId + "' not found.");
            return CommandResult.ERROR;
        }

        FleetParamsV3 params = new FleetParamsV3(
                Global.getSector().getPlayerFleet().getLocationInHyperspace(),
                factionId,
                null, // quality override — use faction default
                FleetTypes.PATROL_MEDIUM,
                120f, // combat FP
                20f,  // freighter
                20f,  // tanker
                0f,   // transport
                0f,   // liner
                0f,   // utility
                0f    // quality mod
        );
        params.withOfficers = true;

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null) {
            Console.showMessage("Failed to create fleet for faction '" + factionId + "'.");
            return CommandResult.ERROR;
        }

        // Spawn near the player
        fleet.setLocation(
                Global.getSector().getPlayerFleet().getLocation().x + 200f,
                Global.getSector().getPlayerFleet().getLocation().y + 200f
        );
        Global.getSector().getPlayerFleet().getContainingLocation().addEntity(fleet);
        fleet.setFacing((float) (Math.random() * 360f));

        Console.showMessage("Spawned " + faction.getDisplayName() + " patrol fleet ("
                + fleet.getFleetData().getMembersListCopy().size() + " ships) near player.");
        return CommandResult.SUCCESS;
    }
}

