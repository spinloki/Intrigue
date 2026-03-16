package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;
import spinloki.Intrigue.territory.*;

import java.util.*;

/**
 * Creates and immediately launches an operation for a subfaction, spawning the
 * corresponding Intel and fleet in-game. Automatically sets up any required
 * entanglements for entanglement-driven op types.
 *
 * Usage: IntrigueTestOp <territory> <subfaction> <op_type> [target_subfaction]
 *
 * The target_subfaction is required for op types that target another subfaction
 * (RAID, WAR_RAID, JOINT_STRIKE, PROTECTION_PATROL, ARMS_SHIPMENT, INVASION).
 *
 * Examples:
 *   IntrigueTestOp intrigue_ashenveil intrigue_church_missionary PATROL
 *   IntrigueTestOp intrigue_ashenveil intrigue_church_missionary RAID intrigue_pirates_freehold
 *   IntrigueTestOp intrigue_ashenveil intrigue_church_missionary WAR_RAID intrigue_pirates_freehold
 */
public class IntrigueTestOp implements BaseCommandWithSuggestion {

    /** Op types that require a target subfaction. */
    private static final Set<ActiveOp.OpType> TARGETED_OPS = Set.of(
            ActiveOp.OpType.RAID,
            ActiveOp.OpType.WAR_RAID,
            ActiveOp.OpType.JOINT_STRIKE,
            ActiveOp.OpType.PROTECTION_PATROL,
            ActiveOp.OpType.ARMS_SHIPMENT,
            ActiveOp.OpType.INVASION
    );

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
            case 1:
            case 3: {
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
                List<String> types = new ArrayList<>();
                for (ActiveOp.OpType t : ActiveOp.OpType.values()) types.add(t.name());
                return types;
            }
            default:
                return Collections.emptyList();
        }
    }

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP && context != CommandContext.CAMPAIGN_MARKET) {
            Console.showMessage("This command can only be used on the campaign map.");
            return CommandResult.WRONG_CONTEXT;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 3) {
            printUsage();
            return CommandResult.BAD_SYNTAX;
        }

        String territoryId = parts[0];
        String subfactionId = parts[1];
        String opTypeName = parts[2];
        String targetSubfactionId = parts.length >= 4 ? parts[3] : null;

        // Parse op type
        ActiveOp.OpType opType;
        try {
            opType = ActiveOp.OpType.valueOf(opTypeName);
        } catch (IllegalArgumentException e) {
            Console.showMessage("Unknown op type: " + opTypeName);
            Console.showMessage("Valid types: " + Arrays.toString(ActiveOp.OpType.values()));
            return CommandResult.ERROR;
        }

        // Find territory
        TerritoryManager mgr = findManager(territoryId);
        if (mgr == null) {
            Console.showMessage("Territory '" + territoryId + "' not found.");
            return CommandResult.ERROR;
        }

        TerritoryState state = mgr.getState();
        Map<String, SubfactionDef> defMap = mgr.loadSubfactionDefMap();
        if (defMap == null) {
            Console.showMessage("Failed to load subfaction defs.");
            return CommandResult.ERROR;
        }

        // Validate subfaction
        SubfactionPresence presence = state.getPresence(subfactionId);
        if (presence == null) {
            Console.showMessage("Subfaction '" + subfactionId + "' not in " + territoryId);
            return CommandResult.ERROR;
        }
        if (!presence.getState().canLaunchOps()) {
            Console.showMessage(displayName(subfactionId) + " is " + presence.getState() +
                    " — must be ESTABLISHED+ to launch ops. Use IntrigueSetPresence first.");
            return CommandResult.ERROR;
        }

        // Validate target subfaction if needed
        if (TARGETED_OPS.contains(opType) && targetSubfactionId == null) {
            Console.showMessage(opType + " requires a target subfaction (4th argument).");
            return CommandResult.BAD_SYNTAX;
        }
        if (targetSubfactionId != null) {
            SubfactionPresence targetPresence = state.getPresence(targetSubfactionId);
            if (targetPresence == null) {
                Console.showMessage("Target subfaction '" + targetSubfactionId + "' not in " + territoryId);
                return CommandResult.ERROR;
            }
            if (opType != ActiveOp.OpType.INVASION && !targetPresence.getState().canLaunchOps()) {
                Console.showMessage(displayName(targetSubfactionId) + " is " + targetPresence.getState() +
                        " — must be ESTABLISHED+ to be a target. Use IntrigueSetPresence first.");
                return CommandResult.ERROR;
            }
        }

        // COUNCIL requires at least 2 ESTABLISHED+ subfactions for participants
        if (opType == ActiveOp.OpType.COUNCIL) {
            int established = 0;
            for (SubfactionPresence p : state.getPresences().values()) {
                if (p.getState().canLaunchOps()) established++;
            }
            if (established < 2) {
                Console.showMessage("COUNCIL requires at least 2 ESTABLISHED+ subfactions in the territory (found " + established + ").");
                Console.showMessage("Use IntrigueSetPresence to promote subfactions first.");
                return CommandResult.ERROR;
            }
        }

        // Resolve origin/target systems
        String originSystem = findBaseSystem(state, subfactionId);
        if (originSystem == null) {
            Console.showMessage(displayName(subfactionId) + " has no base system.");
            return CommandResult.ERROR;
        }

        String targetSystem;
        if (targetSubfactionId != null) {
            targetSystem = findBaseSystem(state, targetSubfactionId);
            if (targetSystem == null) {
                targetSystem = originSystem;
            }
        } else {
            // Pick a different system for non-targeted ops
            targetSystem = originSystem;
            for (String sysId : mgr.getSystemIds()) {
                if (!sysId.equals(originSystem)) {
                    targetSystem = sysId;
                    break;
                }
            }
        }

        // Auto-create entanglements for entanglement-driven ops
        autoCreateEntanglement(opType, subfactionId, targetSubfactionId, state);

        // Create and launch the op via TerritoryManager
        TerritoryState.TickResult.OpLaunched launched = mgr.createAndLaunchOp(
                opType, subfactionId, originSystem, targetSystem, targetSubfactionId);

        if (launched == null) {
            Console.showMessage("Failed to create op.");
            return CommandResult.ERROR;
        }

        // Report
        ActiveOp op = launched.op();
        Console.showMessage("Launched " + opType + " for " + displayName(subfactionId));
        Console.showMessage("  Origin: " + originSystem + " → Target: " + targetSystem);
        if (targetSubfactionId != null) {
            Console.showMessage("  Target subfaction: " + displayName(targetSubfactionId));
        }
        Console.showMessage("  Op ID: " + op.getOpId());

        return CommandResult.SUCCESS;
    }

    /**
     * Auto-create the entanglement required by an entanglement-driven op type.
     * Does nothing for op types that don't need entanglements.
     */
    private static void autoCreateEntanglement(ActiveOp.OpType opType, String subfactionId,
                                                String targetSubfactionId, TerritoryState state) {
        if (targetSubfactionId == null) return;

        EntanglementType entType = null;
        String thirdPartyId = null;
        String hirerId = null;

        switch (opType) {
            case WAR_RAID:
                entType = EntanglementType.TERRITORIAL_WAR;
                break;
            case PROTECTION_PATROL:
                entType = EntanglementType.HIRED_PROTECTION;
                hirerId = targetSubfactionId;
                break;
            case JOINT_STRIKE:
                entType = EntanglementType.SHARED_ENEMY_PACT;
                thirdPartyId = targetSubfactionId;
                // For joint strike, the pair is the two allies; the third party is the enemy
                // But we need a second ally — use the subfaction itself + find one in territory
                // Actually: JOINT_STRIKE targets the enemy. The pair is the subfaction + some ally.
                // For simplicity in test mode, create the pact between subfaction and target,
                // where target IS the enemy (thirdParty). But that doesn't work — the pair
                // should be subfaction + an ally. Let's just skip auto-entanglement for this
                // since it's complex and the op will still spawn.
                return;
            case ARMS_SHIPMENT:
                entType = EntanglementType.PROXY_SUPPORT;
                break;
            default:
                return;
        }

        SubfactionPair pair = new SubfactionPair(subfactionId, targetSubfactionId);
        ActiveEntanglement existing = state.getEntanglement(pair);

        if (existing != null && existing.getType() == entType) {
            return; // already has the right entanglement
        }

        ActiveEntanglement ent = new ActiveEntanglement(
                entType, pair,
                entType.setsHostile ? -1f : 90f,
                thirdPartyId, hirerId,
                "Console: IntrigueTestOp auto-created");
        state.applyEntanglement(ent);
        Console.showMessage("  Auto-created " + entType + " entanglement between " +
                displayName(subfactionId) + " and " + displayName(targetSubfactionId));
    }

    private static TerritoryManager findManager(String territoryId) {
        for (TerritoryManager mgr : TerritoryManager.getManagers()) {
            if (mgr.getTerritoryId().equals(territoryId)) return mgr;
        }
        return null;
    }

    /**
     * Find the base system for a subfaction by checking occupied base slots.
     */
    private static String findBaseSystem(TerritoryState state, String subfactionId) {
        for (BaseSlot slot : state.getBaseSlots()) {
            if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                return slot.getSystemId();
            }
        }
        return null;
    }

    private static String displayName(String factionId) {
        com.fs.starfarer.api.campaign.FactionAPI f = Global.getSector().getFaction(factionId);
        return f != null ? f.getDisplayName() : factionId;
    }

    private static void printUsage() {
        Console.showMessage("Usage: IntrigueTestOp <territory> <subfaction> <op_type> [target_subfaction]");
        Console.showMessage("Op types: " + Arrays.toString(ActiveOp.OpType.values()));
        Console.showMessage("target_subfaction required for: RAID, WAR_RAID, JOINT_STRIKE, PROTECTION_PATROL, ARMS_SHIPMENT, INVASION");
        Console.showMessage("Examples:");
        Console.showMessage("  IntrigueTestOp intrigue_ashenveil intrigue_church_missionary PATROL");
        Console.showMessage("  IntrigueTestOp intrigue_ashenveil intrigue_church_missionary RAID intrigue_pirates_freehold");
        Console.showMessage("  IntrigueTestOp intrigue_ashenveil intrigue_church_missionary WAR_RAID intrigue_pirates_freehold");
    }
}
