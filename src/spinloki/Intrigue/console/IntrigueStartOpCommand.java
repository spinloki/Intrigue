package spinloki.Intrigue.console;

import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.IntrigueOpsManager;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Console command to manually start an Intrigue op for any subfaction.
 *
 * <pre>
 * Usage:
 *   intrigue_start_op &lt;subfaction_id&gt; &lt;op_type&gt; [target]
 *
 * Op types and their required target argument:
 *   raid                   &lt;target_subfaction_id&gt;
 *   establish_base         (none - for homeless criminal subfactions)
 *   scout_territory        &lt;territory_id&gt;
 *   establish_territory_base &lt;territory_id&gt;
 *   patrol                 &lt;territory_id&gt;
 *   send_supplies          &lt;territory_id&gt;
 *   rally                  (none)
 *   infighting             &lt;territory_id&gt;
 *   expulsion              &lt;territory_id&gt;
 *   civil_war              (none)
 * </pre>
 */
public class IntrigueStartOpCommand implements BaseCommandWithSuggestion {

    /** All supported op type keywords in display order. */
    private static final List<String> OP_TYPES = List.of(
            "raid",
            "establish_base",
            "scout_territory",
            "establish_territory_base",
            "patrol",
            "send_supplies",
            "rally",
            "infighting",
            "expulsion",
            "civil_war",
            "mischief"
    );

    /** Op types that require a target subfaction argument. */
    private static final Set<String> NEEDS_TARGET_SUBFACTION = Set.of("raid", "mischief");

    /** Op types that require a territory argument. */
    private static final Set<String> NEEDS_TERRITORY = Set.of(
            "scout_territory", "establish_territory_base",
            "patrol", "send_supplies",
            "infighting", "expulsion",
            "mischief"
    );

    // ── Command execution ───────────────────────────────────────────────

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        if (!IntrigueServices.isInitialized()) {
            Console.showMessage("IntrigueServices not initialized. Run intrigue_init first.");
            return CommandResult.ERROR;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            printUsage();
            return CommandResult.BAD_SYNTAX;
        }

        // ── Resolve subfaction ──────────────────────────────────────────
        String sfToken = parts[0];
        IntrigueSubfaction subfaction = IntrigueServices.subfactions().getById(sfToken);
        if (subfaction == null) {
            Console.showMessage("Unknown subfaction: " + sfToken);
            Console.showMessage("Available: " + allSubfactionIds());
            return CommandResult.ERROR;
        }

        // ── Resolve op type ────────────────────────────────────────────
        String opType = parts[1].toLowerCase(Locale.ROOT);
        if (!OP_TYPES.contains(opType)) {
            Console.showMessage("Unknown op type: " + parts[1]);
            Console.showMessage("Available: " + String.join(", ", OP_TYPES));
            return CommandResult.ERROR;
        }

        // ── Resolve third argument (target subfaction or territory) ────
        String thirdArg = parts.length >= 3 ? parts[2] : null;
        String fourthArg = parts.length >= 4 ? parts[3] : null;

        if (NEEDS_TARGET_SUBFACTION.contains(opType) && NEEDS_TERRITORY.contains(opType)) {
            // Needs both: e.g. mischief <target_subfaction> <territory>
            if (thirdArg == null || fourthArg == null) {
                Console.showMessage("Op type '" + opType + "' requires a target subfaction ID AND a territory ID.");
                Console.showMessage("Usage: intrigue_start_op <subfaction> " + opType + " <target_subfaction> <territory>");
                return CommandResult.BAD_SYNTAX;
            }
        } else if (NEEDS_TARGET_SUBFACTION.contains(opType)) {
            if (thirdArg == null) {
                Console.showMessage("Op type '" + opType + "' requires a target subfaction ID.");
                return CommandResult.BAD_SYNTAX;
            }
        } else if (NEEDS_TERRITORY.contains(opType)) {
            if (thirdArg == null) {
                Console.showMessage("Op type '" + opType + "' requires a territory ID.");
                return CommandResult.BAD_SYNTAX;
            }
        }

        // ── Create the op via the factory ──────────────────────────────
        IntrigueOpsManager opsManager = IntrigueOpsManager.get();
        String opId = opsManager.nextOpId("console");
        IntrigueOp op;

        try {
            op = createOp(opType, opId, subfaction, thirdArg, fourthArg);
        } catch (IllegalArgumentException e) {
            Console.showMessage("Error: " + e.getMessage());
            return CommandResult.ERROR;
        }

        if (op == null) {
            Console.showMessage("Failed to create op (factory returned null).");
            return CommandResult.ERROR;
        }

        // ── Start it ───────────────────────────────────────────────────
        opsManager.startOp(op);
        Console.showMessage("Started " + op.getOpTypeName() + " [" + op.getOpId() + "]"
                + " for " + subfaction.getName() + " (" + subfaction.getSubfactionId() + ")"
                + (op.getTargetSubfactionId() != null ? " → " + op.getTargetSubfactionId() : "")
                + (op.getTerritoryId() != null ? " in territory " + op.getTerritoryId() : ""));

        return CommandResult.SUCCESS;
    }

    // ── Op creation ─────────────────────────────────────────────────────

    private IntrigueOp createOp(String opType, String opId,
                                IntrigueSubfaction subfaction, String thirdArg, String fourthArg) {
        switch (opType) {
            case "raid": {
                IntrigueSubfaction target = IntrigueServices.subfactions().getById(thirdArg);
                if (target == null) {
                    throw new IllegalArgumentException("Unknown target subfaction: " + thirdArg
                            + ". Available: " + allSubfactionIds());
                }
                return IntrigueServices.opFactory().createRaidOp(opId, subfaction, target);
            }
            case "establish_base":
                return IntrigueServices.opFactory().createEstablishBaseOp(opId, subfaction);
            case "scout_territory": {
                validateTerritory(thirdArg);
                return IntrigueServices.opFactory().createScoutTerritoryOp(opId, subfaction, thirdArg);
            }
            case "establish_territory_base": {
                validateTerritory(thirdArg);
                return IntrigueServices.opFactory().createEstablishTerritoryBaseOp(opId, subfaction, thirdArg);
            }
            case "patrol": {
                validateTerritory(thirdArg);
                return IntrigueServices.opFactory().createPatrolOp(opId, subfaction, thirdArg);
            }
            case "send_supplies": {
                validateTerritory(thirdArg);
                return IntrigueServices.opFactory().createSendSuppliesOp(opId, subfaction, thirdArg);
            }
            case "rally":
                return IntrigueServices.opFactory().createRallyOp(opId, subfaction);
            case "infighting": {
                validateTerritory(thirdArg);
                return IntrigueServices.opFactory().createInfightingOp(opId, subfaction, thirdArg);
            }
            case "expulsion": {
                validateTerritory(thirdArg);
                return IntrigueServices.opFactory().createExpulsionOp(opId, subfaction, thirdArg);
            }
            case "civil_war":
                return IntrigueServices.opFactory().createCivilWarOp(opId, subfaction);
            case "mischief": {
                IntrigueSubfaction victim = IntrigueServices.subfactions().getById(thirdArg);
                if (victim == null) {
                    throw new IllegalArgumentException("Unknown target subfaction: " + thirdArg
                            + ". Available: " + allSubfactionIds());
                }
                validateTerritory(fourthArg);
                return IntrigueServices.opFactory().createMischiefOp(opId, subfaction, victim, fourthArg, null);
            }
            default:
                throw new IllegalArgumentException("Unhandled op type: " + opType);
        }
    }

    private void validateTerritory(String territoryId) {
        if (IntrigueServices.territories() == null) {
            throw new IllegalArgumentException("Territories not available.");
        }
        IntrigueTerritory territory = IntrigueServices.territories().getById(territoryId);
        if (territory == null) {
            throw new IllegalArgumentException("Unknown territory: " + territoryId
                    + ". Available: " + allTerritoryIds());
        }
    }

    // ── Suggestions ─────────────────────────────────────────────────────

    @Override
    public List<String> getSuggestions(int parameter, List<String> previous, CommandContext context) {
        if (!context.isInCampaign() || !IntrigueServices.isInitialized()) {
            return Collections.emptyList();
        }

        switch (parameter) {
            case 0: // subfaction ID
                return allSubfactionIdList();
            case 1: // op type
                return new ArrayList<>(OP_TYPES);
            case 2: // target (depends on selected op type)
                return suggestThirdArg(previous);
            default:
                return Collections.emptyList();
        }
    }

    private List<String> suggestThirdArg(List<String> previous) {
        String opType = (previous != null && previous.size() >= 2)
                ? previous.get(1).toLowerCase(Locale.ROOT)
                : "";

        if (NEEDS_TARGET_SUBFACTION.contains(opType)) {
            return allSubfactionIdList();
        }
        if (NEEDS_TERRITORY.contains(opType)) {
            return allTerritoryIdList();
        }
        // No third argument needed
        return Collections.emptyList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String allSubfactionIds() {
        return IntrigueServices.subfactions().getAll().stream()
                .map(IntrigueSubfaction::getSubfactionId)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static List<String> allSubfactionIdList() {
        return IntrigueServices.subfactions().getAll().stream()
                .map(IntrigueSubfaction::getSubfactionId)
                .sorted()
                .collect(Collectors.toList());
    }

    private static String allTerritoryIds() {
        if (IntrigueServices.territories() == null) return "(none)";
        return IntrigueServices.territories().getAll().stream()
                .map(IntrigueTerritory::getTerritoryId)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static List<String> allTerritoryIdList() {
        if (IntrigueServices.territories() == null) return Collections.emptyList();
        return IntrigueServices.territories().getAll().stream()
                .map(IntrigueTerritory::getTerritoryId)
                .sorted()
                .collect(Collectors.toList());
    }

    private void printUsage() {
        Console.showMessage("Usage: intrigue_start_op <subfaction_id> <op_type> [target]");
        Console.showMessage("");
        Console.showMessage("Op types:");
        Console.showMessage("  raid <target_subfaction>     - Launch a raid against another subfaction");
        Console.showMessage("  establish_base               - Establish a criminal base (homeless only)");
        Console.showMessage("  scout_territory <territory>  - Scout a territory (NONE → SCOUTING)");
        Console.showMessage("  establish_territory_base <territory> - Establish a territory base (SCOUTING → ESTABLISHED)");
        Console.showMessage("  patrol <territory>           - Patrol an established territory");
        Console.showMessage("  send_supplies <territory>    - Send a supply convoy to a territory");
        Console.showMessage("  rally                        - Rally home base cohesion");
        Console.showMessage("  infighting <territory>       - Trigger infighting in a territory");
        Console.showMessage("  expulsion <territory>        - Expel the subfaction from a territory");
        Console.showMessage("  civil_war                    - Trigger a civil war in the subfaction");
    }
}


