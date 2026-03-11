package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionDoctrineAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;
import spinloki.Intrigue.territory.ActiveOp;
import spinloki.Intrigue.territory.BaseSlot;
import spinloki.Intrigue.territory.SubfactionPresence;
import spinloki.Intrigue.territory.TerritoryGenerationPlugin;
import spinloki.Intrigue.territory.TerritoryManager;
import spinloki.Intrigue.territory.TerritoryPatrolIntel;

import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Console command that prints the status of all Intrigue territories and subfactions.
 * Usage: IntrigueStatus
 */
public class IntrigueStatus implements BaseCommand {

    @Override
    @SuppressWarnings("unchecked")
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP && context != CommandContext.CAMPAIGN_MARKET) {
            Console.showMessage("IntrigueStatus can only be used on the campaign map.");
            return CommandResult.WRONG_CONTEXT;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== INTRIGUE STATUS ===\n");

        // --- Territories ---
        Map<String, List<String>> territories = (Map<String, List<String>>)
                Global.getSector().getPersistentData().get(TerritoryGenerationPlugin.PERSISTENT_KEY);

        if (territories == null || territories.isEmpty()) {
            sb.append("\nNo territories found in persistent data.\n");
        } else {
            sb.append("\n--- Territories ---\n");
            for (Map.Entry<String, List<String>> entry : territories.entrySet()) {
                sb.append("  ").append(entry.getKey())
                        .append(": ").append(entry.getValue().size()).append(" systems")
                        .append(" ").append(entry.getValue())
                        .append("\n");
            }
        }

        // --- Subfactions ---
        List<SubfactionDef> defs = (List<SubfactionDef>)
                Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);

        if (defs == null || defs.isEmpty()) {
            sb.append("\nNo subfaction definitions found in persistent data.\n");
        } else {
            sb.append("\n--- Subfactions ---\n");
            for (SubfactionDef def : defs) {
                FactionAPI faction = Global.getSector().getFaction(def.id);
                if (faction == null) {
                    sb.append("  ").append(def.name).append(" [").append(def.id)
                            .append("] — MISSING FACTION!\n");
                    continue;
                }

                FactionDoctrineAPI d = faction.getDoctrine();
                Color color = faction.getBaseUIColor();
                String colorStr = String.format("(%d,%d,%d)", color.getRed(), color.getGreen(), color.getBlue());

                sb.append("  ").append(faction.getDisplayName())
                        .append(" [").append(def.id).append("]\n");
                sb.append("    Parent: ").append(def.parentFactionId).append("\n");
                sb.append("    Color: ").append(colorStr).append("\n");
                sb.append("    Doctrine: warships=").append(d.getWarships())
                        .append(" carriers=").append(d.getCarriers())
                        .append(" phase=").append(d.getPhaseShips())
                        .append(" shipSize=").append(d.getShipSize())
                        .append(" aggression=").append(d.getAggression()).append("\n");
                sb.append("    Quality: ships=").append(d.getShipQuality())
                        .append(" officers=").append(d.getOfficerQuality())
                        .append(" numShips=").append(d.getNumShips()).append("\n");

                // Show fleet modifiers from SubfactionDef
                sb.append("    Fleet Mods: sizeMult=").append(String.format("%.2f", def.fleetSizeMult))
                        .append(" qualityMod=").append(String.format("%.2f", def.fleetQualityMod))
                        .append("\n");

                // Show relationship to parent
                FactionAPI parent = Global.getSector().getFaction(def.parentFactionId);
                if (parent != null) {
                    float rel = faction.getRelationship(def.parentFactionId);
                    sb.append("    Rel to parent (").append(parent.getDisplayName())
                            .append("): ").append(String.format("%.1f", rel))
                            .append(" (").append(faction.getRelationshipLevel(def.parentFactionId))
                            .append(")\n");
                }

                // Show a few key relationships
                String[] checkFactions = {"player", "hegemony", "tritachyon", "pirates", "luddic_church"};
                sb.append("    Key relations: ");
                for (String fid : checkFactions) {
                    if (fid.equals(def.id) || fid.equals(def.parentFactionId)) continue;
                    FactionAPI f = Global.getSector().getFaction(fid);
                    if (f == null) continue;
                    sb.append(f.getDisplayName()).append("=")
                            .append(faction.getRelationshipLevel(fid)).append(" ");
                }
                sb.append("\n");
            }
        }

        // --- Territory Presence (Managers) ---
        List<TerritoryManager> managers = TerritoryManager.getManagers();
        if (managers.isEmpty()) {
            sb.append("\nNo territory managers found.\n");
        } else {
            sb.append("\n--- Territory Presence ---\n");
            for (TerritoryManager mgr : managers) {
                int totalSlots = mgr.getBaseSlots().size();
                int occupiedSlots = 0;
                for (BaseSlot s : mgr.getBaseSlots()) {
                    if (s.isOccupied()) occupiedSlots++;
                }

                sb.append("  ").append(mgr.getTerritoryId())
                        .append(" (").append(mgr.getSystemIds().size()).append(" systems, ")
                        .append(totalSlots).append(" base slots, ")
                        .append(occupiedSlots).append(" occupied)\n");

                if (mgr.getPresences().isEmpty()) {
                    sb.append("    (no subfactions)\n");
                } else {
                    for (SubfactionPresence presence : mgr.getPresences().values()) {
                        FactionAPI sf = Global.getSector().getFaction(presence.getSubfactionId());
                        String displayName = sf != null ? sf.getDisplayName() : presence.getSubfactionId();
                        sb.append("    ").append(displayName)
                                .append(" — ").append(presence.getState())
                                .append(" (").append(String.format("%.1f", presence.getDaysSinceStateChange()))
                                .append(" days)\n");
                    }
                }

                // Show base slots
                if (!mgr.getBaseSlots().isEmpty()) {
                    sb.append("    Base Slots:\n");
                    for (BaseSlot slot : mgr.getBaseSlots()) {
                        sb.append("      ").append(slot.getSlotType())
                                .append(" — ").append(slot.getLabel());
                        if (slot.isOccupied()) {
                            FactionAPI owner = Global.getSector().getFaction(slot.getOccupiedBySubfactionId());
                            String ownerName = owner != null ? owner.getDisplayName() : slot.getOccupiedBySubfactionId();
                            sb.append(" [OCCUPIED by ").append(ownerName).append("]");
                        } else {
                            sb.append(" [available]");
                        }
                        sb.append("\n");
                    }
                }

                // Show active ops
                List<ActiveOp> ops = mgr.getState().getActiveOps();
                if (!ops.isEmpty()) {
                    sb.append("    Active Ops:\n");
                    for (ActiveOp op : ops) {
                        FactionAPI opFaction = Global.getSector().getFaction(op.getSubfactionId());
                        String opOwner = opFaction != null ? opFaction.getDisplayName() : op.getSubfactionId();
                        sb.append("      ").append(op.getType())
                                .append(" — ").append(opOwner)
                                .append(" (").append(op.getOriginSystemId())
                                .append(" → ").append(op.getTargetSystemId()).append(")")
                                .append(" [").append(String.format("%.0f", op.getDaysRemaining())).append("d left")
                                .append(", ").append(op.getOutcome()).append("]");

                        // Show if fleet was destroyed (intel tracking)
                        TerritoryPatrolIntel intel = mgr.getActiveIntels().get(op.getOpId());
                        if (intel != null && intel.wasFleetDestroyed()) {
                            sb.append(" FLEET DESTROYED");
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        sb.append("\n=== END INTRIGUE STATUS ===");
        Console.showMessage(sb.toString());
        return CommandResult.SUCCESS;
    }
}

