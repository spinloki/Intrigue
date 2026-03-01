package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.IntrigueOpsManager;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Console command that starts a Rally op for one subfaction, then starts
 * a Mischief op against it from a second subfaction, and tells the player
 * where to look for the resulting fleets.
 *
 * <pre>
 * Usage:
 *   intrigue_test_rally_mischief                     — picks two random subfactions
 *   intrigue_test_rally_mischief &lt;rally_sf&gt;          — picks a random mischief initiator
 *   intrigue_test_rally_mischief &lt;rally_sf&gt; &lt;mischief_sf&gt; — uses both specified
 * </pre>
 */
public class IntrigueTestRallyMischiefCommand implements BaseCommand {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        if (!IntrigueServices.isInitialized()) {
            Console.showMessage("IntrigueServices not initialized. Run intrigue_init first.");
            return CommandResult.ERROR;
        }

        // ── Gather eligible subfactions (must have a home market) ───────
        List<IntrigueSubfaction> eligible = IntrigueServices.subfactions().getAll().stream()
                .filter(IntrigueSubfaction::hasHomeMarket)
                .collect(Collectors.toList());

        if (eligible.size() < 2) {
            Console.showMessage("Need at least 2 subfactions with home markets. Found: " + eligible.size());
            return CommandResult.ERROR;
        }

        // ── Parse arguments ─────────────────────────────────────────────
        String[] parts = args.trim().isEmpty() ? new String[0] : args.trim().split("\\s+");
        Random rng = new Random();

        IntrigueSubfaction rallySf;
        IntrigueSubfaction mischiefSf;

        if (parts.length >= 2) {
            rallySf = IntrigueServices.subfactions().getById(parts[0]);
            mischiefSf = IntrigueServices.subfactions().getById(parts[1]);
            if (rallySf == null) {
                Console.showMessage("Unknown rally subfaction: " + parts[0]);
                return CommandResult.ERROR;
            }
            if (mischiefSf == null) {
                Console.showMessage("Unknown mischief subfaction: " + parts[1]);
                return CommandResult.ERROR;
            }
        } else if (parts.length == 1) {
            rallySf = IntrigueServices.subfactions().getById(parts[0]);
            if (rallySf == null) {
                Console.showMessage("Unknown rally subfaction: " + parts[0]);
                return CommandResult.ERROR;
            }
            List<IntrigueSubfaction> others = eligible.stream()
                    .filter(sf -> !sf.getSubfactionId().equals(rallySf.getSubfactionId()))
                    .collect(Collectors.toList());
            if (others.isEmpty()) {
                Console.showMessage("No other subfaction available for mischief.");
                return CommandResult.ERROR;
            }
            mischiefSf = others.get(rng.nextInt(others.size()));
        } else {
            Collections.shuffle(eligible, rng);
            rallySf = eligible.get(0);
            mischiefSf = eligible.get(1);
        }

        if (rallySf.getSubfactionId().equals(mischiefSf.getSubfactionId())) {
            Console.showMessage("Rally and mischief subfactions must be different.");
            return CommandResult.ERROR;
        }

        if (!rallySf.hasHomeMarket()) {
            Console.showMessage(rallySf.getName() + " has no home market — can't rally.");
            return CommandResult.ERROR;
        }
        if (!mischiefSf.hasHomeMarket()) {
            Console.showMessage(mischiefSf.getName() + " has no home market — can't cause mischief.");
            return CommandResult.ERROR;
        }

        IntrigueOpsManager ops = IntrigueOpsManager.get();

        // ── 1. Start the Rally op ──────────────────────────────────────
        String rallyOpId = ops.nextOpId("console_rally");
        IntrigueOp rallyOp = IntrigueServices.opFactory().createRallyOp(rallyOpId, rallySf);
        ops.startOp(rallyOp);

        Console.showMessage("─── Rally ───");
        Console.showMessage("  " + rallySf.getName() + " (" + rallySf.getSubfactionId()
                + ") started a Rally [" + rallyOpId + "]");

        // ── 2. Start the Mischief op targeting the rally ───────────────
        // Mischief needs a territory; use the first shared territory, or null
        String territoryId = findSharedTerritory(rallySf, mischiefSf);

        String mischiefOpId = ops.nextOpId("console_mischief");
        IntrigueOp mischiefOp = IntrigueServices.opFactory().createMischiefOp(
                mischiefOpId, mischiefSf, rallySf, territoryId, rallyOp);
        ops.startOp(mischiefOp);

        Console.showMessage("─── Mischief ───");
        Console.showMessage("  " + mischiefSf.getName() + " (" + mischiefSf.getSubfactionId()
                + ") started Mischief [" + mischiefOpId + "] targeting the rally");

        // ── 3. Hint where to find the fleets ───────────────────────────
        Console.showMessage("");
        Console.showMessage("─── Where to look ───");

        String rallyMarketId = rallySf.getHomeMarketId();
        MarketAPI rallyMarket = Global.getSector().getEconomy().getMarket(rallyMarketId);
        if (rallyMarket != null && rallyMarket.getPrimaryEntity() != null) {
            String systemName = rallyMarket.getPrimaryEntity().getContainingLocation().getName();
            Console.showMessage("  Rally fleet:    " + rallyMarket.getName()
                    + " in " + systemName + " (market: " + rallyMarketId + ")");
            Console.showMessage("  Mischief fleet: same location — disruptors will patrol around the rally");
            Console.showMessage("");
            Console.showMessage("  Tip: the mischief fleet plays " + mischiefSf.getName()
                    + "'s hostile music theme when you get close.");
        } else {
            Console.showMessage("  Rally is at market '" + rallyMarketId
                    + "' (couldn't resolve location — market may be missing).");
        }

        Console.showMessage("");
        Console.showMessage("  Both fleets spawn via RouteManager when the player enters the system.");
        Console.showMessage("  Travel to " + (rallyMarket != null ? rallyMarket.getName() : rallyMarketId)
                + " to see them in action.");

        return CommandResult.SUCCESS;
    }

    /**
     * Find the first territory where both subfactions have presence.
     * Returns null if none found (mischief can still work without one).
     */
    private String findSharedTerritory(IntrigueSubfaction a, IntrigueSubfaction b) {
        if (IntrigueServices.territories() == null) return null;
        for (var territory : IntrigueServices.territories().getAll()) {
            boolean aPresent = territory.getPresence(a.getSubfactionId()).ordinal() > 0;
            boolean bPresent = territory.getPresence(b.getSubfactionId()).ordinal() > 0;
            if (aPresent && bPresent) return territory.getTerritoryId();
        }
        // Fall back to any territory either has presence in
        for (var territory : IntrigueServices.territories().getAll()) {
            if (territory.getPresence(a.getSubfactionId()).ordinal() > 0) {
                return territory.getTerritoryId();
            }
        }
        return null;
    }
}



