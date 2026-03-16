package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;
import spinloki.Intrigue.territory.*;

import java.util.*;

/**
 * Directly sets a subfaction's presence state in a territory.
 * Handles base spawning/destruction and station tier changes as needed.
 *
 * Usage: IntrigueSetPresence <territory> <subfaction> <state>
 *
 * Examples:
 *   IntrigueSetPresence intrigue_ashenveil intrigue_church_missionary FORTIFIED
 *   IntrigueSetPresence intrigue_ashenveil intrigue_pirates_freehold NONE
 */
public class IntrigueSetPresence implements BaseCommandWithSuggestion {

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getSuggestions(int argIndex, List<String> args, CommandContext context) {
        switch (argIndex) {
            case 0: {
                List<String> ids = new ArrayList<>();
                for (TerritoryManager mgr : TerritoryManager.getManagers()) {
                    ids.add(mgr.getTerritoryId());
                }
                return ids;
            }
            case 1: {
                if (args.size() >= 1) {
                    TerritoryManager mgr = findManager(args.get(0));
                    if (mgr != null) {
                        return new ArrayList<>(mgr.getPresences().keySet());
                    }
                }
                List<SubfactionDef> defs = (List<SubfactionDef>)
                        Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
                if (defs != null) {
                    List<String> ids = new ArrayList<>();
                    for (SubfactionDef def : defs) ids.add(def.id);
                    return ids;
                }
                return Collections.emptyList();
            }
            case 2: {
                List<String> states = new ArrayList<>();
                for (PresenceState s : PresenceState.values()) states.add(s.name());
                return states;
            }
            default:
                return Collections.emptyList();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP && context != CommandContext.CAMPAIGN_MARKET) {
            Console.showMessage("This command can only be used on the campaign map.");
            return CommandResult.WRONG_CONTEXT;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 3) {
            Console.showMessage("Usage: IntrigueSetPresence <territory> <subfaction> <state>");
            Console.showMessage("States: " + Arrays.toString(PresenceState.values()));
            return CommandResult.BAD_SYNTAX;
        }

        String territoryId = parts[0];
        String subfactionId = parts[1];
        String stateName = parts[2];

        // Parse target state
        PresenceState targetState;
        try {
            targetState = PresenceState.valueOf(stateName);
        } catch (IllegalArgumentException e) {
            Console.showMessage("Unknown state: " + stateName);
            Console.showMessage("Valid states: " + Arrays.toString(PresenceState.values()));
            return CommandResult.ERROR;
        }

        // Find territory
        TerritoryManager mgr = findManager(territoryId);
        if (mgr == null) {
            Console.showMessage("Territory '" + territoryId + "' not found.");
            return CommandResult.ERROR;
        }

        TerritoryState state = mgr.getState();

        // Validate subfaction is present
        SubfactionPresence presence = state.getPresence(subfactionId);
        if (presence == null) {
            Console.showMessage("Subfaction '" + subfactionId + "' not in " + territoryId);
            return CommandResult.ERROR;
        }

        PresenceState oldState = presence.getState();
        if (oldState == targetState) {
            Console.showMessage(displayName(subfactionId) + " is already " + targetState);
            return CommandResult.SUCCESS;
        }

        // Load subfaction def (needed for base spawning)
        List<SubfactionDef> defs = (List<SubfactionDef>)
                Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
        SubfactionDef def = null;
        if (defs != null) {
            for (SubfactionDef d : defs) {
                if (d.id.equals(subfactionId)) { def = d; break; }
            }
        }

        // Handle setting to NONE — evict
        if (targetState == PresenceState.NONE) {
            // Release base slot if occupied
            for (BaseSlot slot : state.getBaseSlots()) {
                if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                    slot.release();
                    break;
                }
            }
            presence.setState(PresenceState.NONE);
            Console.showMessage(displayName(subfactionId) + ": " + oldState + " → NONE (evicted)");
            return CommandResult.SUCCESS;
        }

        // Handle setting to SCOUTING — destroy base if they have one
        if (targetState == PresenceState.SCOUTING) {
            for (BaseSlot slot : state.getBaseSlots()) {
                if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                    slot.release();
                    break;
                }
            }
            presence.setState(PresenceState.SCOUTING);
            Console.showMessage(displayName(subfactionId) + ": " + oldState + " → SCOUTING");
            return CommandResult.SUCCESS;
        }

        // Target is ESTABLISHED, FORTIFIED, or DOMINANT — need a base
        boolean needsBase = !oldState.canLaunchOps() && targetState.canLaunchOps();

        if (needsBase) {
            // Subfaction doesn't have a base yet — spawn one
            if (def == null) {
                Console.showMessage("No SubfactionDef found for " + subfactionId + ", can't spawn base.");
                return CommandResult.ERROR;
            }

            BaseSlot slot = state.pickSlot(def);
            if (slot == null) {
                Console.showMessage("No available base slots in " + territoryId);
                return CommandResult.ERROR;
            }

            SectorEntityToken station = BaseSpawner.spawnBase(slot, def);
            if (station == null) {
                Console.showMessage("Failed to spawn base for " + displayName(subfactionId));
                return CommandResult.ERROR;
            }

            state.confirmEstablishment(subfactionId, slot);
            slot.setStationEntityId(station.getId());
            Console.showMessage("  Spawned base at " + slot.getLabel());
        }

        // Set the state
        presence.setState(targetState);

        // Adjust station tier if the subfaction has a base
        if (targetState.canLaunchOps()) {
            for (BaseSlot slot : state.getBaseSlots()) {
                if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                    if (BaseSpawner.setStationTier(slot, targetState)) {
                        Console.showMessage("  Station upgraded to match " + targetState);
                    }
                    break;
                }
            }
        }

        Console.showMessage(displayName(subfactionId) + ": " + oldState + " → " + targetState);
        return CommandResult.SUCCESS;
    }

    private static TerritoryManager findManager(String territoryId) {
        for (TerritoryManager mgr : TerritoryManager.getManagers()) {
            if (mgr.getTerritoryId().equals(territoryId)) return mgr;
        }
        return null;
    }

    private static String displayName(String factionId) {
        com.fs.starfarer.api.campaign.FactionAPI f = Global.getSector().getFaction(factionId);
        return f != null ? f.getDisplayName() : factionId;
    }
}
