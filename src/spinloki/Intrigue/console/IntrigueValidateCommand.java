package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.config.SubfactionConfig;
import spinloki.Intrigue.config.SubfactionConfigLoader;

import java.util.*;

/**
 * Console command that validates the bootstrapped subfactions and people
 * against the JSON config and the live game state.
 *
 * Checks:
 *   - Every config subfaction was created
 *   - Every person exists as a PersonAPI in important people
 *   - Person name, faction, role, traits match the config
 *   - Person is placed at the correct home market
 *   - Subfaction leader/member wiring is consistent
 *
 * Usage: intrigue_validate
 */
public class IntrigueValidateCommand implements BaseCommand {

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

        String trimmedArgs = args != null ? args.trim() : "";

        // Subcommand: list all markets by faction
        if (trimmedArgs.equalsIgnoreCase("markets")) {
            return listMarketsByFaction();
        }

        Console.showMessage("=== Intrigue Validation ===\n");

        boolean fix = trimmedArgs.equalsIgnoreCase("fix");
        if (fix) {
            Console.showMessage("(Running in FIX mode — will attempt to correct placement errors)\n");
        }

        int errors = 0;
        int warnings = 0;
        int fixed = 0;
        int checked = 0;

        // Load the config to compare against
        SubfactionConfig config = SubfactionConfigLoader.load();
        if (config.subfactions == null || config.subfactions.isEmpty()) {
            Console.showMessage("ERROR: Could not load subfaction config.");
            return CommandResult.ERROR;
        }

        Console.showMessage("Config defines " + config.subfactions.size() + " subfactions.\n");

        for (SubfactionConfig.SubfactionDef def : config.subfactions) {
            checked++;
            Console.showMessage("--- " + def.name + " [" + def.subfactionId + "] ---");

            // 1. Check subfaction exists in the manager
            IntrigueSubfaction sf = IntrigueServices.subfactions().getById(def.subfactionId);
            if (sf == null) {
                Console.showMessage("  ERROR: Subfaction not found in manager!");
                errors++;
                continue;
            }

            // 2. Validate faction
            FactionAPI faction = Global.getSector().getFaction(sf.getFactionId());
            if (faction == null) {
                Console.showMessage("  ERROR: Faction '" + sf.getFactionId() + "' does not exist in game.");
                errors++;
            } else if (!sf.getFactionId().equals(def.factionId)) {
                Console.showMessage("  ERROR: Faction mismatch: config=" + def.factionId + " actual=" + sf.getFactionId());
                errors++;
            } else {
                Console.showMessage("  Faction: " + sf.getFactionId() + " OK");
            }

            // 3. Validate home market
            MarketAPI market = Global.getSector().getEconomy().getMarket(sf.getHomeMarketId());
            if (market == null) {
                Console.showMessage("  ERROR: Home market '" + sf.getHomeMarketId() + "' does not exist in game.");
                errors++;
            } else if (!sf.getHomeMarketId().equals(def.homeMarketId)) {
                Console.showMessage("  ERROR: Market mismatch: config=" + def.homeMarketId + " actual=" + sf.getHomeMarketId());
                errors++;
            } else {
                Console.showMessage("  Market: " + market.getName() + " (" + sf.getHomeMarketId() + ") OK");
            }

            // 4. Check cohesion/legitimacy
            Console.showMessage("  " + sf.getCohesionLabel() + ": " + sf.getCohesion()
                    + " (config=" + def.cohesion + ")");
            Console.showMessage("  " + sf.getLegitimacyLabel() + ": " + sf.getLegitimacy()
                    + " (config=" + def.legitimacy + ")");

            // 5. Validate each member from config
            for (SubfactionConfig.MemberDef mDef : def.members) {
                String expectedName = mDef.firstName + " " + mDef.lastName;
                boolean isLeader = "LEADER".equals(mDef.role);

                // Find the matching IntriguePerson by scanning subfaction members
                IntriguePerson matchedIp = null;
                PersonAPI matchedPerson = null;

                // Check leader
                if (isLeader && sf.getLeaderId() != null) {
                    IntriguePerson leaderIp = IntrigueServices.people().getById(sf.getLeaderId());
                    PersonAPI leaderP = Global.getSector().getImportantPeople().getPerson(sf.getLeaderId());
                    if (leaderP != null && expectedName.equals(leaderP.getName().getFullName())) {
                        matchedIp = leaderIp;
                        matchedPerson = leaderP;
                    }
                }

                // Check all member IDs
                if (matchedIp == null) {
                    for (String memberId : sf.getMemberIds()) {
                        PersonAPI p = Global.getSector().getImportantPeople().getPerson(memberId);
                        if (p != null && expectedName.equals(p.getName().getFullName())) {
                            matchedIp = IntrigueServices.people().getById(memberId);
                            matchedPerson = p;
                            break;
                        }
                    }
                }

                String roleLabel = isLeader ? "LEADER" : "MEMBER";

                if (matchedPerson == null) {
                    Console.showMessage("  ERROR: " + roleLabel + " '" + expectedName + "' not found as PersonAPI!");
                    errors++;
                    continue;
                }

                if (matchedIp == null) {
                    Console.showMessage("  ERROR: " + roleLabel + " '" + expectedName + "' has no IntriguePerson data!");
                    errors++;
                    continue;
                }

                StringBuilder line = new StringBuilder();
                line.append("  ").append(roleLabel).append(": ").append(matchedPerson.getName().getFullName());
                line.append(" [").append(matchedIp.getPersonId()).append("]");

                List<String> issues = new ArrayList<>();

                // Validate role
                if (isLeader && matchedIp.getRole() != IntriguePerson.Role.LEADER) {
                    issues.add("role should be LEADER, is " + matchedIp.getRole());
                } else if (!isLeader && matchedIp.getRole() != IntriguePerson.Role.MEMBER) {
                    issues.add("role should be MEMBER, is " + matchedIp.getRole());
                }

                // Validate faction on person
                if (!def.factionId.equals(matchedIp.getFactionId())) {
                    issues.add("faction should be " + def.factionId + ", is " + matchedIp.getFactionId());
                }

                // Validate home market on person
                if (!def.homeMarketId.equals(matchedIp.getHomeMarketId())) {
                    issues.add("homeMarket should be " + def.homeMarketId + ", is " + matchedIp.getHomeMarketId());
                }

                // Validate subfaction ID on person
                if (!def.subfactionId.equals(matchedIp.getSubfactionId())) {
                    issues.add("subfactionId should be " + def.subfactionId + ", is " + matchedIp.getSubfactionId());
                }

                // Validate traits
                Set<String> expectedTraits = new LinkedHashSet<>(mDef.traits != null ? mDef.traits : Collections.emptyList());
                Set<String> actualTraits = matchedIp.getTraits();
                if (!expectedTraits.equals(actualTraits)) {
                    issues.add("traits mismatch: expected=" + expectedTraits + " actual=" + actualTraits);
                }

                // Validate person is placed at home market
                if (market != null) {
                    boolean foundAtMarket = false;
                    for (PersonAPI p : market.getPeopleCopy()) {
                        if (p.getId().equals(matchedIp.getPersonId())) {
                            foundAtMarket = true;
                            break;
                        }
                    }
                    if (!foundAtMarket) {
                        // Find where they actually are
                        String actualLocation = "unknown";
                        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                            for (PersonAPI p : m.getPeopleCopy()) {
                                if (p.getId().equals(matchedIp.getPersonId())) {
                                    actualLocation = m.getName() + " (" + m.getId() + ")";
                                    break;
                                }
                            }
                        }
                        issues.add("NOT at home market " + market.getName()
                                + " — actually at: " + actualLocation
                                + " (personId=" + matchedIp.getPersonId()
                                + ", locType=" + matchedIp.getLocationType()
                                + ", locId=" + matchedIp.getLocationId() + ")");

                        if (fix) {
                            IntrigueServices.people().returnHome(matchedIp.getPersonId());
                            fixed++;
                        }
                    }
                }

                if (issues.isEmpty()) {
                    line.append(" OK");
                } else {
                    for (String issue : issues) {
                        errors++;
                        line.append("\n    ERROR: ").append(issue);
                    }
                }

                Console.showMessage(line.toString());
            }

            // 6. Check leader wiring
            if (sf.getLeaderId() == null) {
                Console.showMessage("  ERROR: Subfaction has no leader assigned!");
                errors++;
            }

            Console.showMessage("");
        }

        // 7. Check for orphaned subfactions (in manager but not in config)
        Set<String> configIds = new HashSet<>();
        for (SubfactionConfig.SubfactionDef def : config.subfactions) {
            configIds.add(def.subfactionId);
        }
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            if (!configIds.contains(sf.getSubfactionId())) {
                Console.showMessage("WARNING: Subfaction '" + sf.getSubfactionId()
                        + "' exists in manager but not in config (orphaned?)");
                warnings++;
            }
        }

        // Summary
        Console.showMessage("=== Validation Complete ===");
        Console.showMessage("Checked: " + checked + " subfactions");
        Console.showMessage("Errors: " + errors);
        Console.showMessage("Warnings: " + warnings);
        if (fix && fixed > 0) {
            Console.showMessage("Fixed: " + fixed + " placement(s)");
        }

        if (errors == 0 && warnings == 0) {
            Console.showMessage("All subfactions and people validated successfully.");
        } else if (!fix && errors > 0) {
            Console.showMessage("\nRun 'intrigue_validate fix' to attempt automatic corrections.");
        }

        return errors == 0 ? CommandResult.SUCCESS : CommandResult.ERROR;
    }

    private CommandResult listMarketsByFaction() {
        Console.showMessage("=== All Markets by Faction ===\n");

        Map<String, List<MarketAPI>> byFaction = new TreeMap<>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m == null) continue;
            String fid = m.getFactionId();
            if (fid == null) fid = "(null)";
            byFaction.computeIfAbsent(fid, k -> new ArrayList<>()).add(m);
        }

        for (Map.Entry<String, List<MarketAPI>> entry : byFaction.entrySet()) {
            Console.showMessage(entry.getKey() + ":");
            for (MarketAPI m : entry.getValue()) {
                Console.showMessage("  " + m.getName() + " [" + m.getId() + "] size=" + m.getSize());
            }
            Console.showMessage("");
        }

        return CommandResult.SUCCESS;
    }
}


