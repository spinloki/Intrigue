package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.OpEvaluator;
import spinloki.Intrigue.campaign.ops.OpOutcome;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.config.SubfactionConfig;
import spinloki.Intrigue.config.TerritoryConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Integration test that runs the REAL OpEvaluator and RaidOp code
 * against sim-side SPI implementations — proving the DI works
 * with the subfaction model.
 *
 * Run via: java SimIntegrationTest
 */
public class SimIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║     Intrigue DI Integration Tests (Subfactions)  ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        testServicesInitialization();
        testSubfactionNameField();
        testPersonConfigConstructor();
        testOpEvaluatorUsesSimClock();
        testSubfactionRaidOpLifecycle();
        testSameFactionTargeting();
        testCooldownOnSubfaction();
        testPowerShiftsOnSubfactions();
        testSubfactionNameSurvivesOpLifecycle();
        testPersonToPersonRelationships();
        testConfigJsonRoundTrip();
        testTerritoryPresenceLifecycle();
        testPatrolOpLifecycle();
        testFullSimLoop();

        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);
        System.out.println("═══════════════════════════════════════════════════");

        // Informational — not a pass/fail test
        runLongSimStats();

        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Set up a sim with 3 subfactions across 2 factions:
     *   - sf_heg1: hegemony subfaction, leader=leader1 (MERCILESS), member=member1, power=60
     *   - sf_heg2: hegemony subfaction, leader=leader2, power=45 (internal rival)
     *   - sf_tri:  tritachyon subfaction, leader=leader3, member=member2, power=50
     */
    static SimClock setupSim() {
        IntrigueServices.reset();
        SimClock clock = new SimClock();
        SimPeopleAccess people = new SimPeopleAccess();
        SimOpRunner ops = new SimOpRunner();
        SimOpFactory opFactory = new SimOpFactory(new Random(100), SimConfig.defaults());
        SimSubfactionAccess subfactions = new SimSubfactionAccess();

        // ── People ──
        IntriguePerson leader1 = new IntriguePerson("leader1", "hegemony", "heg_market_1");
        leader1.setRole(IntriguePerson.Role.LEADER);
        leader1.setSubfactionId("sf_heg1");
        leader1.getTraits().add(IntrigueTraits.MERCILESS);

        IntriguePerson member1 = new IntriguePerson("member1", "hegemony", "heg_market_1");
        member1.setRole(IntriguePerson.Role.MEMBER);
        member1.setSubfactionId("sf_heg1");
        member1.setBonus("Commander skills for patrol fleets");

        IntriguePerson leader2 = new IntriguePerson("leader2", "hegemony", "heg_market_2");
        leader2.setRole(IntriguePerson.Role.LEADER);
        leader2.setSubfactionId("sf_heg2");

        IntriguePerson leader3 = new IntriguePerson("leader3", "tritachyon", "tri_market");
        leader3.setRole(IntriguePerson.Role.LEADER);
        leader3.setSubfactionId("sf_tri");

        IntriguePerson member2 = new IntriguePerson("member2", "tritachyon", "tri_market");
        member2.setRole(IntriguePerson.Role.MEMBER);
        member2.setSubfactionId("sf_tri");
        member2.setBonus("Access to midline hull designs");

        people.addPerson(leader1);
        people.addPerson(member1);
        people.addPerson(leader2);
        people.addPerson(leader3);
        people.addPerson(member2);

        // ── Subfactions ──
        IntrigueSubfaction sfHeg1 = new IntrigueSubfaction("sf_heg1", "Eventide Aristocracy", "hegemony", "heg_market_1");
        sfHeg1.setHomeCohesion(55);
        sfHeg1.setLegitimacy(65);
        sfHeg1.setCohesionLabel("Discipline");
        sfHeg1.setLegitimacyLabel("Authority");
        sfHeg1.setLeaderId("leader1");
        sfHeg1.getMemberIds().add("member1");

        IntrigueSubfaction sfHeg2 = new IntrigueSubfaction("sf_heg2", "14th Battlegroup Detachment", "hegemony", "heg_market_2");
        sfHeg2.setHomeCohesion(50);
        sfHeg2.setLegitimacy(40);
        sfHeg2.setCohesionLabel("Discipline");
        sfHeg2.setLegitimacyLabel("Authority");
        sfHeg2.setLeaderId("leader2");

        IntrigueSubfaction sfTri = new IntrigueSubfaction("sf_tri", "Tri-Tachyon Capital Assurance", "tritachyon", "tri_market");
        sfTri.setHomeCohesion(55);
        sfTri.setLegitimacy(45);
        sfTri.setCohesionLabel("Synergy");
        sfTri.setLegitimacyLabel("Mandate");
        sfTri.setLeaderId("leader3");
        sfTri.getMemberIds().add("member2");

        subfactions.addSubfaction(sfHeg1);
        subfactions.addSubfaction(sfHeg2);
        subfactions.addSubfaction(sfTri);

        // ── Territories ──
        SimTerritoryAccess territories = new SimTerritoryAccess();
        IntrigueTerritory remnantFrontier = new IntrigueTerritory(
                "territory_remnant_frontier", "Remnant Frontier",
                TerritoryConfig.Tier.HIGH, "Remnant activity has spiked.");
        remnantFrontier.addInterestedFaction("hegemony");
        remnantFrontier.addInterestedFaction("tritachyon");
        remnantFrontier.addConstellationName("Alpha Constellation");
        territories.addTerritory(remnantFrontier);

        IntrigueTerritory domainCache = new IntrigueTerritory(
                "territory_domain_cache", "Domain Cache",
                TerritoryConfig.Tier.MEDIUM, "Domain-era supply caches.");
        domainCache.addInterestedFaction("tritachyon");
        domainCache.addConstellationName("Beta Constellation");
        territories.addTerritory(domainCache);

        IntrigueServices.init(clock, people, ops, opFactory, subfactions,
                // Sim hostility: different factions are always hostile
                (a, b) -> a != null && b != null && !a.equals(b),
                territories);
        return clock;
    }
    /**
     * Set up the sim from the actual intrigue_subfactions.json config file.
     * Parses the JSON, creates IntriguePerson + IntrigueSubfaction objects,
     * and registers them with IntrigueServices.
     */
    static SimClock setupSimFromConfig(String configPath) throws IOException {
        String json = Files.readString(Path.of(configPath));
        SubfactionConfig config = SubfactionConfig.parseFromJson(json);
        IntrigueServices.reset();
        SimClock clock = new SimClock();
        SimPeopleAccess people = new SimPeopleAccess();
        SimOpRunner ops = new SimOpRunner();
        SimOpFactory opFactory = new SimOpFactory(new Random(100), SimConfig.defaults());
        SimSubfactionAccess subfactions = new SimSubfactionAccess();
        Set<String> allFactionIds = new LinkedHashSet<>();
        for (SubfactionConfig.SubfactionDef def : config.subfactions) {
            allFactionIds.add(def.factionId);
            IntrigueSubfaction.SubfactionType sfType = IntrigueSubfaction.SubfactionType.POLITICAL;
            if ("CRIMINAL".equals(def.type)) {
                sfType = IntrigueSubfaction.SubfactionType.CRIMINAL;
            }
            IntrigueSubfaction sf = new IntrigueSubfaction(
                    def.subfactionId, def.name, def.factionId, def.homeMarketId, sfType);
            sf.setHomeCohesion(def.cohesion);
            sf.setLegitimacy(def.legitimacy);
            if (def.cohesionLabel != null) sf.setCohesionLabel(def.cohesionLabel);
            if (def.legitimacyLabel != null) sf.setLegitimacyLabel(def.legitimacyLabel);
            int memberIdx = 0;
            for (SubfactionConfig.MemberDef m : def.members) {
                IntriguePerson.Role role = IntriguePerson.Role.MEMBER;
                if ("LEADER".equals(m.role)) role = IntriguePerson.Role.LEADER;
                String personId = def.subfactionId + "_" + m.role.toLowerCase() + "_" + memberIdx;
                IntriguePerson person = new IntriguePerson(
                        personId, def.factionId, def.homeMarketId,
                        def.subfactionId, role, m.bonus);
                if (m.traits != null) {
                    for (String trait : m.traits) {
                        person.getTraits().add(trait);
                    }
                }
                people.addPerson(person);
                if (role == IntriguePerson.Role.LEADER) {
                    sf.setLeaderId(personId);
                } else {
                    sf.getMemberIds().add(personId);
                }
                memberIdx++;
            }
            subfactions.addSubfaction(sf);
        }
        // Territories — load from intrigue_territories.json if available, else hardcoded fallback
        SimTerritoryAccess territories = new SimTerritoryAccess();
        String territoriesPath = System.getProperty("intrigue.territories");
        if (territoriesPath != null) {
            String terrJson = Files.readString(Path.of(territoriesPath));
            TerritoryConfig terrConfig = TerritoryConfig.parseFromJson(terrJson);
            for (TerritoryConfig.TerritoryDef tDef : terrConfig.territories) {
                IntrigueTerritory t = new IntrigueTerritory(
                        tDef.territoryId, tDef.name, tDef.tier, tDef.plotHook);
                for (int ci = 0; ci < tDef.numConstellations; ci++) {
                    t.addConstellationName(tDef.name + " Constellation " + (ci + 1));
                }
                if (tDef.interestedFactions != null) {
                    for (String fid : tDef.interestedFactions) {
                        t.addInterestedFaction(fid);
                    }
                }
                territories.addTerritory(t);
            }
            System.out.printf("  Loaded %d territories from: %s%n",
                    terrConfig.territories.size(), territoriesPath);
        } else {
            // Hardcoded fallback — two territories, all non-criminal factions interested
            IntrigueTerritory remnantFrontier = new IntrigueTerritory(
                    "territory_remnant_frontier", "Remnant Frontier",
                    TerritoryConfig.Tier.HIGH, "Remnant activity has spiked.");
            remnantFrontier.addConstellationName("Alpha Constellation");
            IntrigueTerritory domainCache = new IntrigueTerritory(
                    "territory_domain_cache", "Domain Cache",
                    TerritoryConfig.Tier.MEDIUM, "Domain-era supply caches.");
            domainCache.addConstellationName("Beta Constellation");
            for (SubfactionConfig.SubfactionDef def2 : config.subfactions) {
                if (!"CRIMINAL".equals(def2.type)) {
                    remnantFrontier.addInterestedFaction(def2.factionId);
                    domainCache.addInterestedFaction(def2.factionId);
                }
            }
            territories.addTerritory(remnantFrontier);
            territories.addTerritory(domainCache);
        }
        IntrigueServices.init(clock, people, ops, opFactory, subfactions,
                (a, b) -> a != null && b != null && !a.equals(b),
                territories);
        System.out.printf("  Loaded %d subfactions (%d factions) from: %s%n",
                config.subfactions.size(), allFactionIds.size(), configPath);
        return clock;
    }
    static void testServicesInitialization() {
        test("IntrigueServices initializes with sim implementations", () -> {
            setupSim();
            assertTrue("isInitialized", IntrigueServices.isInitialized());
            assertNotNull("clock", IntrigueServices.clock());
            assertNotNull("people", IntrigueServices.people());
            assertNotNull("ops", IntrigueServices.ops());
            assertNotNull("subfactions", IntrigueServices.subfactions());
        });
    }

    static void testSubfactionNameField() {
        test("Subfaction name field works with both constructors", () -> {
            setupSim();

            // 4-arg named constructor
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");
            assertNotNull("sf_heg1 exists", sfHeg1);
            assertEquals("sf_heg1 name", "Eventide Aristocracy", sfHeg1.getName());
            assertEquals("sf_heg1 faction", "hegemony", sfHeg1.getFactionId());

            IntrigueSubfaction sfHeg2 = IntrigueServices.subfactions().getById("sf_heg2");
            assertEquals("sf_heg2 name", "14th Battlegroup Detachment", sfHeg2.getName());

            IntrigueSubfaction sfTri = IntrigueServices.subfactions().getById("sf_tri");
            assertEquals("sf_tri name", "Tri-Tachyon Capital Assurance", sfTri.getName());

            // 3-arg fallback constructor uses id as name
            IntrigueSubfaction fallback = new IntrigueSubfaction("test_id", "some_faction", "some_market");
            assertEquals("fallback name equals id", "test_id", fallback.getName());
        });
    }

    static void testPersonConfigConstructor() {
        test("IntriguePerson 6-arg constructor sets all fields", () -> {
            IntriguePerson ip = new IntriguePerson(
                    "test_person", "hegemony", "heg_market",
                    "test_sf", IntriguePerson.Role.LEADER, "Commander skills");

            assertEquals("personId", "test_person", ip.getPersonId());
            assertEquals("factionId", "hegemony", ip.getFactionId());
            assertEquals("homeMarketId", "heg_market", ip.getHomeMarketId());
            assertEquals("subfactionId", "test_sf", ip.getSubfactionId());
            assertEquals("role", IntriguePerson.Role.LEADER, ip.getRole());
            assertTrue("isLeader", ip.isLeader());
            assertEquals("bonus", "Commander skills", ip.getBonus());
            assertFalse("not checked out", ip.isCheckedOut());
        });
    }

    static void testOpEvaluatorUsesSimClock() {
        test("OpEvaluator uses sim clock for subfaction cooldown", () -> {
            SimClock clock = setupSim();
            IntrigueSubfaction sf = IntrigueServices.subfactions().getById("sf_heg1");

            // Set a recent op timestamp
            clock.advanceDays(10);
            sf.setLastOpTimestamp(clock.getTimestamp());
            clock.advanceDays(5); // only 5 days since last op

            // Should be on cooldown (30 days required)
            IntrigueOp op = OpEvaluator.evaluate(sf, IntrigueServices.ops(), "test");
            assertNull("Should be on cooldown", op);

            // Advance past cooldown
            clock.advanceDays(30);
            IntrigueOp op2 = OpEvaluator.evaluate(sf, IntrigueServices.ops(), "test");
            assertNotNull("Should produce an op after cooldown", op2);
        });
    }

    static void testSubfactionRaidOpLifecycle() {
        test("Subfaction raid op lifecycle runs in sim", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");
            IntriguePerson leader1 = IntrigueServices.people().getById("leader1");

            // Remove hegemony interest from all territories so the evaluator
            // skips territory ops and falls through to raids
            for (IntrigueTerritory t : IntrigueServices.territories().getAll()) {
                t.removeInterestedFaction("hegemony");
            }

            int powerBefore = sfHeg1.getPower();

            // Evaluate — sf_heg1 should create an op (MERCILESS leader, power 60)
            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should create an op", op);
            assertEquals("Op type", "Raid", op.getOpTypeName());

            ops.startOp(op);
            assertTrue("leader1 checked out", leader1.isCheckedOut());
            assertTrue("has active op", ops.hasActiveOp("leader1"));

            // Advance to resolution
            for (int i = 0; i < 100; i++) {
                clock.advanceDays(1);
                ops.advance(1f);
                if (op.isResolved()) break;
            }

            assertTrue("Op should resolve", op.isResolved());
            assertFalse("leader1 should be back home", leader1.isCheckedOut());
            // Power should have changed on the subfaction
            assertTrue("Subfaction power changed", sfHeg1.getPower() != powerBefore);
        });
    }

    static void testSameFactionTargeting() {
        test("Same-faction subfactions cannot target each other", () -> {
            setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");

            // sf_heg1 (hegemony, MERCILESS) will produce a territory op or a raid.
            // If it's a raid, the target must be cross-faction (not sf_heg2).
            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should create an op", op);

            if (op.getTargetSubfactionId() != null) {
                assertEquals("Target must be cross-faction", "sf_tri", op.getTargetSubfactionId());
            }
            // Territory ops have no target — constraint is trivially satisfied
        });
    }

    static void testCooldownOnSubfaction() {
        test("Cooldown applies to subfaction, not person", () -> {
            SimClock clock = setupSim();
            IntrigueSubfaction sf = IntrigueServices.subfactions().getById("sf_heg1");

            // Set cooldown on subfaction
            clock.advanceDays(10);
            sf.setLastOpTimestamp(clock.getTimestamp());

            // Should be on cooldown even though leader is free
            IntriguePerson leader = IntrigueServices.people().getById("leader1");
            assertFalse("Leader is not checked out", leader.isCheckedOut());

            IntrigueOp op = OpEvaluator.evaluate(sf, IntrigueServices.ops(), "test");
            assertNull("Should be on cooldown", op);
        });
    }

    static void testPowerShiftsOnSubfactions() {
        test("Cohesion/legitimacy shifts apply to subfactions after ops", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");

            int cohesionBefore = sfHeg1.getHomeCohesion();
            int legitimacyBefore = sfHeg1.getLegitimacy();

            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should create an op", op);
            ops.startOp(op);

            // Advance to resolution
            for (int i = 0; i < 100; i++) {
                clock.advanceDays(1);
                ops.advance(1f);
                if (op.isResolved()) break;
            }

            assertTrue("Op resolved", op.isResolved());

            // At least one of cohesion or legitimacy should have changed
            boolean changed = sfHeg1.getHomeCohesion() != cohesionBefore
                    || sfHeg1.getLegitimacy() != legitimacyBefore;
            assertTrue("Subfaction stats changed after op", changed);

            // Cooldown should be set on the subfaction
            assertTrue("Cooldown set", sfHeg1.getLastOpTimestamp() > 0);
        });
    }

    static void testSubfactionNameSurvivesOpLifecycle() {
        test("Subfaction name survives through op lifecycle", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");
            assertEquals("Name before op", "Eventide Aristocracy", sfHeg1.getName());
            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should create an op", op);
            ops.startOp(op);
            // Advance to resolution
            for (int i = 0; i < 100; i++) {
                clock.advanceDays(1);
                ops.advance(1f);
                if (op.isResolved()) break;
            }
            assertTrue("Op resolved", op.isResolved());
            assertEquals("Name after op", "Eventide Aristocracy", sfHeg1.getName());
            // Check target subfaction name (if this is a raid, not a territory op)
            if (op.getTargetSubfactionId() != null) {
                IntrigueSubfaction target = IntrigueServices.subfactions().getById(op.getTargetSubfactionId());
                assertNotNull("Target subfaction exists", target);
                assertTrue("Target has a name",
                        target.getName() != null && !target.getName().isEmpty());
            }
        });
    }
    static void testPersonToPersonRelationships() {
        test("Person-to-person relationships are bidirectional and clamped", () -> {
            IntriguePerson a = new IntriguePerson("p_a", "hegemony", "market_1",
                    "sf_1", IntriguePerson.Role.LEADER, null);
            IntriguePerson b = new IntriguePerson("p_b", "hegemony", "market_1",
                    "sf_1", IntriguePerson.Role.MEMBER, "Some bonus");
            IntriguePerson c = new IntriguePerson("p_c", "tritachyon", "market_2",
                    "sf_2", IntriguePerson.Role.LEADER, null);
            // Initially null (no relationship)
            assertTrue("No initial rel a->b", a.getRelTo("p_b") == null);
            assertTrue("No initial rel b->a", b.getRelTo("p_a") == null);
            // Set relationship between a and b
            a.setRelToInternal("p_b", 50);
            b.setRelToInternal("p_a", 50);
            assertEquals("a->b is 50", Integer.valueOf(50), a.getRelTo("p_b"));
            assertEquals("b->a is 50", Integer.valueOf(50), b.getRelTo("p_a"));
            // Negative relationship
            a.setRelToInternal("p_c", -30);
            c.setRelToInternal("p_a", -30);
            assertEquals("a->c is -30", Integer.valueOf(-30), a.getRelTo("p_c"));
            assertEquals("c->a is -30", Integer.valueOf(-30), c.getRelTo("p_a"));
            // b has no relationship with c
            assertTrue("No rel b->c", b.getRelTo("p_c") == null);
            // Relationships don't interfere with relToPlayer
            a.setRelToPlayer(25);
            assertEquals("relToPlayer is separate", 25, a.getRelToPlayer());
            assertEquals("a->b unchanged", Integer.valueOf(50), a.getRelTo("p_b"));
        });
    }
    static void testConfigJsonRoundTrip() {
        test("Config JSON round-trip: parse -> serialize -> parse produces same result", () -> {
            // Locate the config file relative to CWD (run_balance_tests.sh runs from src/)
            Path configPath = Paths.get("../data/config/intrigue_subfactions.json");
            if (!Files.exists(configPath)) {
                // Try from project root
                configPath = Paths.get("data/config/intrigue_subfactions.json");
            }
            assertTrue("Config file exists at " + configPath, Files.exists(configPath));
            String originalJson;
            try {
                originalJson = new String(Files.readAllBytes(configPath));
            } catch (IOException e) {
                throw new AssertionError("Failed to read config file: " + e.getMessage());
            }
            // Parse the original JSON
            SubfactionConfig config1 = SubfactionConfig.parseFromJson(originalJson);
            assertTrue("Parsed subfactions not empty", config1.subfactions != null && !config1.subfactions.isEmpty());
            // Verify we got the expected count (10 subfactions in the config)
            assertEquals("Subfaction count", 14, config1.subfactions.size());
            // Serialize back to JSON
            String serialized = config1.toJson();
            assertTrue("Serialized JSON not empty", serialized != null && !serialized.isEmpty());
            // Parse the serialized JSON (round-trip)
            SubfactionConfig config2 = SubfactionConfig.parseFromJson(serialized);
            assertEquals("Round-trip subfaction count", config1.subfactions.size(), config2.subfactions.size());
            // Compare each subfaction structurally
            for (int i = 0; i < config1.subfactions.size(); i++) {
                SubfactionConfig.SubfactionDef def1 = config1.subfactions.get(i);
                SubfactionConfig.SubfactionDef def2 = config2.subfactions.get(i);
                assertEquals("Subfaction[" + i + "] round-trip", def1, def2);
            }
            // Double round-trip: serialize again, compare ignoring whitespace differences
            String serialized2 = config2.toJson();
            assertEquals("Double round-trip structurally identical",
                    normalizeWhitespace(serialized), normalizeWhitespace(serialized2));
        });
    }
    static void testTerritoryPresenceLifecycle() {
        test("Territory presence: NONE → SCOUTING → ESTABLISHED", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

            // sf_heg1 is hegemony — interested in "Remnant Frontier"
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");
            IntrigueTerritory remnant = IntrigueServices.territories().getById("territory_remnant_frontier");
            assertNotNull("remnant territory", remnant);

            // Initially NONE
            assertEquals("initial presence", IntrigueTerritory.Presence.NONE,
                    remnant.getPresence(sfHeg1.getSubfactionId()));

            // Evaluate should produce a Scout op (no raid targets at high enough score? or territory op)
            // Force evaluate with enough cooldown cleared
            clock.advanceDays(60);
            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should produce an op", op);

            // If it's a territory op, run it
            if (op.getTerritoryId() != null) {
                assertEquals("op type", "Scout Territory", op.getOpTypeName());
                ops.startOp(op);

                // Presence should be SCOUTING now
                assertEquals("presence after start", IntrigueTerritory.Presence.SCOUTING,
                        remnant.getPresence(sfHeg1.getSubfactionId()));

                // Advance to completion
                for (int i = 0; i < 40; i++) {
                    clock.advanceDays(1);
                    ops.advance(1f);
                    if (op.isResolved()) break;
                }
                assertTrue("scout op resolved", op.isResolved());

                // Still SCOUTING after success
                assertEquals("presence after scout success", IntrigueTerritory.Presence.SCOUTING,
                        remnant.getPresence(sfHeg1.getSubfactionId()));

                // Now evaluate again — should produce an Establish Territory Base op
                clock.advanceDays(60); // clear cooldown
                IntrigueOp op2 = OpEvaluator.evaluate(sfHeg1, ops, "test");
                assertNotNull("Should produce establish op", op2);
                assertEquals("op2 type", "Establish Territory Base", op2.getOpTypeName());

                ops.startOp(op2);
                for (int i = 0; i < 60; i++) {
                    clock.advanceDays(1);
                    ops.advance(1f);
                    if (op2.isResolved()) break;
                }
                assertTrue("establish op resolved", op2.isResolved());

                // Should be ESTABLISHED now
                assertEquals("presence after establish", IntrigueTerritory.Presence.ESTABLISHED,
                        remnant.getPresence(sfHeg1.getSubfactionId()));

                // Should have territory cohesion set
                assertTrue("territory cohesion > 0", remnant.getCohesion(sfHeg1.getSubfactionId()) > 0);
            } else {
                // It produced a raid op instead — that's fine, just check territory is still NONE
                assertEquals("presence unchanged", IntrigueTerritory.Presence.NONE,
                        remnant.getPresence(sfHeg1.getSubfactionId()));
            }
        });
    }

    static void testPatrolOpLifecycle() {
        test("Patrol op: ESTABLISHED territory → patrol → legitimacy change", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");
            IntrigueTerritory remnant = IntrigueServices.territories().getById("territory_remnant_frontier");

            // Pre-set sf_heg1 to ESTABLISHED in all interested territories
            for (IntrigueTerritory t : IntrigueServices.territories().getAll()) {
                if (t.isFactionInterested(sfHeg1.getFactionId())) {
                    t.setPresence(sfHeg1.getSubfactionId(), IntrigueTerritory.Presence.ESTABLISHED);
                    t.setCohesion(sfHeg1.getSubfactionId(), 50);
                }
            }

            int legitimacyBefore = sfHeg1.getLegitimacy();

            // Evaluate — with ESTABLISHED presence, should produce a Patrol op
            clock.advanceDays(60);
            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should produce an op", op);
            assertEquals("op type", "Patrol", op.getOpTypeName());
            assertNotNull("has territory", op.getTerritoryId());

            ops.startOp(op);

            // Advance to completion
            for (int i = 0; i < 40; i++) {
                clock.advanceDays(1);
                ops.advance(1f);
                if (op.isResolved()) break;
            }
            assertTrue("patrol op resolved", op.isResolved());

            // Legitimacy should have changed (either +3 on success or -4 on failure)
            int legitimacyAfter = sfHeg1.getLegitimacy();
            assertTrue("Legitimacy changed after patrol",
                    legitimacyAfter != legitimacyBefore);

            if (op.getOutcome() == OpOutcome.SUCCESS) {
                assertEquals("Legitimacy gained on success",
                        legitimacyBefore + 3, legitimacyAfter);
            } else {
                assertEquals("Legitimacy lost on failure",
                        legitimacyBefore - 4, legitimacyAfter);
            }
        });
    }

    static void runLongSimStats() {
        // Configurable tick count
        int ticks = 200;
        String ticksProp = System.getProperty("intrigue.ticks");
        if (ticksProp != null) {
            try { ticks = Integer.parseInt(ticksProp); } catch (NumberFormatException ignored) {}
        }

        System.out.printf("%n╔═══════════════════════════════════════════════════╗%n");
        System.out.printf("║       %d-Tick Simulation Statistics             ║%n", ticks);
        System.out.printf("╚═══════════════════════════════════════════════════╝%n%n");

        SimConfig config = SimConfig.defaults();

        System.out.println("  Config:");
        System.out.printf("    Establish Base success:       %.0f%%%n", config.establishBaseSuccessProb * 100);
        System.out.printf("    Scout Territory success:      %.0f%%%n", config.scoutTerritorySuccessProb * 100);
        System.out.printf("    Establish Terr. Base success: %.0f%%%n", config.establishTerritoryBaseSuccessProb * 100);
        System.out.printf("    Patrol success:               %.0f%%%n", config.patrolSuccessProb * 100);
        System.out.printf("    Send Supplies success:        %.0f%%%n", config.sendSuppliesSuccessProb * 100);
        System.out.printf("    Send Supplies cohesion gain:  +%d%n", config.sendSuppliesCohesionGain);
        System.out.printf("    Send Supplies cohesion loss:  -%d%n", config.sendSuppliesCohesionLoss);
        System.out.printf("    Rally success:                %d%%%n", (int) (config.rallySuccessProb * 100));
        System.out.printf("    Rally cohesion gain:          +%d%n", config.rallyCohesionGain);
        System.out.printf("    Rally threshold:              <%d home cohesion%n", config.rallyCohesionThreshold);
        System.out.printf("    Territory cohesion decay/tick: %d%n", config.territoryCohesionDecayPerTick);
        System.out.printf("    Infighting threshold:         <%d cohesion%n", config.infightingCohesionThreshold);
        System.out.printf("    Expulsion threshold:          <%d cohesion for %d ticks%n",
                config.expulsionCohesionThreshold, config.expulsionTicksRequired);
        System.out.printf("    Civil War threshold:          <%d home cohesion for %d ticks%n",
                config.civilWarCohesionThreshold, config.civilWarTicksRequired);
        System.out.printf("    High legitimacy threshold:    %d%n", config.highLegitimacyThreshold);
        System.out.printf("    Low legitimacy threshold:     %d (patrol > raids)%n", OpEvaluator.LOW_LEGITIMACY_THRESHOLD);
        System.out.printf("    Critical legitimacy threshold:%d (patrol > supplies)%n", OpEvaluator.CRITICAL_LEGITIMACY_THRESHOLD);
        System.out.printf("    Op cooldown:                  %.0f days%n", (float) OpEvaluator.COOLDOWN_DAYS);
        System.out.printf("    Min cohesion threshold:       %d%n", OpEvaluator.MIN_COHESION_THRESHOLD);
        System.out.printf("    Concurrent ops:               max %d, extra costs %d totalCoh each, drains %d coh/tick%n",
                config.maxConcurrentOps, config.extraOpCohesionCost, config.concurrentOpDrainPerTick);
        System.out.printf("    Player action interval:       every %d ticks%n", config.playerActionInterval);
        System.out.printf("    Player favor bonus:           %+.0f%%%n", config.playerFavorBonus * 100);
        System.out.printf("    Player disfavor penalty:      %+.0f%%%n", config.playerDisfavorPenalty * 100);
        System.out.println();

        SimClock clock;
        String configPath = System.getProperty("intrigue.config");
        if (configPath != null) {
            try {
                clock = setupSimFromConfig(configPath);
            } catch (IOException e) {
                System.err.println("ERROR: Failed to load config from " + configPath + ": " + e.getMessage());
                return;
            }
        } else {
            clock = setupSim();
        }
        SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

        float daysPerTick = 7f;

        // Snapshot starting state
        Map<String, int[]> startStats = new LinkedHashMap<>(); // sfId -> [cohesion, legitimacy]
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            startStats.put(sf.getSubfactionId(),
                    new int[]{sf.getHomeCohesion(), sf.getLegitimacy()});
        }

        Map<String, Map<String, int[]>> startTerritoryCohesion = new LinkedHashMap<>();
        // sfId -> { territoryId -> [cohesion, presenceOrdinal] }
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            Map<String, int[]> perTerritory = new LinkedHashMap<>();
            for (IntrigueTerritory t : IntrigueServices.territories().getAll()) {
                perTerritory.put(t.getTerritoryId(), new int[]{
                        t.getCohesion(sf.getSubfactionId()),
                        t.getPresence(sf.getSubfactionId()).ordinal()
                });
            }
            startTerritoryCohesion.put(sf.getSubfactionId(), perTerritory);
        }

        // Op counters: sfId -> { opType -> count }
        Map<String, Map<String, Integer>> opCounts = new LinkedHashMap<>();
        // Outcome counters: sfId -> { opType -> [successes, failures] }
        Map<String, Map<String, int[]>> outcomeCounts = new LinkedHashMap<>();
        // Dysfunction event counters: sfId -> [infightings, expulsions, civilWars]
        Map<String, int[]> dysfunctionCounts = new LinkedHashMap<>();
        // Vulnerability raid counters: sfId -> [raids_launched, raids_suffered]
        Map<String, int[]> vulnRaidCounts = new LinkedHashMap<>();
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            opCounts.put(sf.getSubfactionId(), new LinkedHashMap<>());
            outcomeCounts.put(sf.getSubfactionId(), new LinkedHashMap<>());
            dysfunctionCounts.put(sf.getSubfactionId(), new int[3]);
            vulnRaidCounts.put(sf.getSubfactionId(), new int[2]);
        }


        // Track ops that need resolution checking
        List<IntrigueOp> pendingOps = new ArrayList<>();
        Map<String, String> opToSubfaction = new LinkedHashMap<>(); // opId -> sfId

        boolean verbose = "true".equals(System.getProperty("intrigue.verbose"));

        // Player mode: "help", "hurt", "both", or null (disabled)
        String playerMode = System.getProperty("intrigue.player");
        boolean playerEnabled = playerMode != null;

        // Configurable player interval
        int playerInterval = config.playerActionInterval;
        String intervalProp = System.getProperty("intrigue.player.interval");
        if (intervalProp != null) {
            try { playerInterval = Integer.parseInt(intervalProp); } catch (NumberFormatException ignored) {}
        }

        // Get resolver for player modifiers
        OpOutcomeResolver resolver = ((SimOpFactory) IntrigueServices.opFactory()).getResolver();
        Random playerRng = new Random(99); // separate RNG so player actions don't disturb op RNG sequence

        // Player action tracking: sfId -> [favors, disfavors]
        Map<String, int[]> playerActions = new LinkedHashMap<>();
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            playerActions.put(sf.getSubfactionId(), new int[2]);
        }
        String currentTargetId = null; // only one faction at a time

        // Run
        for (int t = 0; t < ticks; t++) {

            // ── Player intervention: pick one faction, clear the previous ──
            if (playerEnabled && t > 0 && t % playerInterval == 0) {
                // Clear previous target's modifier
                if (currentTargetId != null) {
                    resolver.setSubfactionModifier(currentTargetId, 0f);
                }

                List<IntrigueSubfaction> allSf = new ArrayList<>(IntrigueServices.subfactions().getAll());
                IntrigueSubfaction target = allSf.get(playerRng.nextInt(allSf.size()));
                currentTargetId = target.getSubfactionId();

                // Determine favor/disfavor based on mode
                boolean favor;
                if ("help".equals(playerMode)) {
                    favor = true;
                } else if ("hurt".equals(playerMode)) {
                    favor = false;
                } else {
                    favor = playerRng.nextBoolean(); // "both" mode
                }

                float mod = favor ? config.playerFavorBonus : config.playerDisfavorPenalty;
                resolver.setSubfactionModifier(currentTargetId, mod);
                int[] pa = playerActions.get(currentTargetId);
                if (favor) pa[0]++; else pa[1]++;

                if (verbose) {
                    System.out.printf("  [t=%3d] ** PLAYER %s %s ** (modifier %+.0f%%)%n",
                            t, favor ? "FAVORS" : "DISFAVORS", target.getName(), mod * 100);
                }
            }
            for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                // Evaluate up to maxConcurrentOps — each call may return a new op
                // until the subfaction runs out of capacity or has nothing to do
                for (int slot = 0; slot < OpEvaluator.MAX_CONCURRENT_OPS; slot++) {
                    IntrigueOp op = OpEvaluator.evaluate(sf, ops, "sim");
                    if (op == null) break; // no more ops to launch

                    ops.startOp(op);
                    opCounts.get(sf.getSubfactionId())
                            .merge(op.getOpTypeName(), 1, Integer::sum);
                    pendingOps.add(op);
                    opToSubfaction.put(op.getOpId(), sf.getSubfactionId());

                    if (verbose) {
                        int activeCount = ops.getActiveOpCount(sf.getLeaderId());
                        int maxOps = OpEvaluator.maxConcurrentOps(sf);
                        System.out.printf("  [t=%3d] %-35s \u2192 %-20s (homeCoh=%d leg=%d ops=%d/%d",
                                t, sf.getName(), op.getOpTypeName(),
                                sf.getHomeCohesion(), sf.getLegitimacy(),
                                activeCount, maxOps);
                        if (op.getTerritoryId() != null) {
                            IntrigueTerritory terr = IntrigueServices.territories().getById(op.getTerritoryId());
                            if (terr != null) {
                                System.out.printf(" terrCoh=%d pres=%s",
                                        terr.getCohesion(sf.getSubfactionId()),
                                        terr.getPresence(sf.getSubfactionId()));
                            }
                        }
                        System.out.println(")");
                    }
                }
            }
            // ── Vulnerability raids: legitimacy == 0 triggers free raids from all hostiles ──
            List<IntrigueOp> vulnRaids = OpEvaluator.evaluateVulnerabilityRaids(ops, "sim");
            for (IntrigueOp vr : vulnRaids) {
                ops.startOp(vr);
                String atkId = vr.getInitiatorSubfactionId();
                String defId = vr.getTargetSubfactionId();
                opCounts.get(atkId).merge(vr.getOpTypeName() + " (free)", 1, Integer::sum);
                pendingOps.add(vr);
                opToSubfaction.put(vr.getOpId(), atkId);
                vulnRaidCounts.get(atkId)[0]++;
                vulnRaidCounts.get(defId)[1]++;
                if (verbose) {
                    IntrigueSubfaction atkSf = IntrigueServices.subfactions().getById(atkId);
                    IntrigueSubfaction defSf = IntrigueServices.subfactions().getById(defId);
                    System.out.printf("  [t=%3d] !! %-35s \u2192 VULNERABILITY RAID vs %-20s (victim leg=0)%n",
                            t,
                            atkSf != null ? atkSf.getName() : atkId,
                            defSf != null ? defSf.getName() : defId);
                }
            }
            clock.advanceDays(daysPerTick);
            ops.advance(daysPerTick);

            // Territory cohesion decay per tick + low-cohesion tick tracking
            for (IntrigueTerritory territory : IntrigueServices.territories().getAll()) {
                for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                    String sfId = sf.getSubfactionId();
                    if (territory.getPresence(sfId) == IntrigueTerritory.Presence.ESTABLISHED) {
                        int before = territory.getCohesion(sfId);
                        territory.setCohesion(sfId, before - config.territoryCohesionDecayPerTick);

                        int after = territory.getCohesion(sfId);
                        // Track low-cohesion ticks for infighting/expulsion
                        if (after < config.infightingCohesionThreshold) {
                            territory.incrementLowCohesionTicks(sfId);
                        } else {
                            territory.resetLowCohesionTicks(sfId);
                        }
                    }
                }
            }

            // Concurrent op cohesion drain: each extra active op (beyond the first)
            // drains cohesion from a random established territory or home
            Random drainRng = new Random(t * 31L + 7);
            for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                String leaderId = sf.getLeaderId();
                if (leaderId == null) continue;
                int activeCount = ops.getActiveOpCount(leaderId);
                int extraOps = activeCount - 1;
                if (extraOps <= 0) continue;

                int drain = extraOps * config.concurrentOpDrainPerTick;

                // Build pool of drain targets: home + established territories
                List<String> drainTargets = new ArrayList<>();
                drainTargets.add("__home__"); // sentinel for home cohesion
                for (IntrigueTerritory terr : IntrigueServices.territories().getAll()) {
                    if (terr.getPresence(sf.getSubfactionId()) == IntrigueTerritory.Presence.ESTABLISHED) {
                        drainTargets.add(terr.getTerritoryId());
                    }
                }

                for (int d = 0; d < drain; d++) {
                    String target = drainTargets.get(drainRng.nextInt(drainTargets.size()));
                    if ("__home__".equals(target)) {
                        sf.setHomeCohesion(sf.getHomeCohesion() - 1);
                    } else {
                        IntrigueTerritory terr = IntrigueServices.territories().getById(target);
                        if (terr != null) {
                            terr.setCohesion(sf.getSubfactionId(),
                                    terr.getCohesion(sf.getSubfactionId()) - 1);
                        }
                    }
                }
            }

            // Home cohesion civil war tick tracking
            for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                if (sf.getHomeCohesion() < config.civilWarCohesionThreshold) {
                    sf.incrementLowHomeCohesionTicks();
                } else {
                    sf.resetLowHomeCohesionTicks();
                }
            }

            // Check for resolved ops and track outcomes
            Iterator<IntrigueOp> it = pendingOps.iterator();
            while (it.hasNext()) {
                IntrigueOp op = it.next();
                if (op.isResolved()) {
                    String sfId = opToSubfaction.get(op.getOpId());
                    int[] counts = outcomeCounts.get(sfId)
                            .computeIfAbsent(op.getOpTypeName(), k -> new int[2]);
                    if (op.getOutcome() == OpOutcome.SUCCESS) {
                        counts[0]++;
                    } else {
                        counts[1]++;
                    }
                    // Track dysfunction events
                    int[] dys = dysfunctionCounts.get(sfId);
                    if (dys != null) {
                        switch (op.getOpTypeName()) {
                            case "Infighting": dys[0]++; break;
                            case "Expulsion":  dys[1]++; break;
                            case "Civil War":  dys[2]++; break;
                        }
                    }
                    if (verbose) {
                        IntrigueSubfaction rsf = null;
                        for (IntrigueSubfaction s : IntrigueServices.subfactions().getAll()) {
                            if (s.getSubfactionId().equals(sfId)) { rsf = s; break; }
                        }
                        String rsfName = rsf != null ? rsf.getName() : sfId;
                        System.out.printf("  [t=%3d]   %-35s   %-20s \u2192 %s",
                                t, rsfName, op.getOpTypeName(), op.getOutcome());
                        if (rsf != null) {
                            System.out.printf("  (homeCoh=%d leg=%d)", rsf.getHomeCohesion(), rsf.getLegitimacy());
                        }
                        System.out.println();
                    }
                    it.remove();
                }
            }
        }

        // Print per-subfaction stats
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            String id = sf.getSubfactionId();
            int[] start = startStats.get(id);
            System.out.printf("  %-35s [%s]%n", sf.getName(), sf.getFactionId());
            System.out.printf("    Home Cohesion:  %3d → %3d  (%+d)%n",
                    start[0], sf.getHomeCohesion(), sf.getHomeCohesion() - start[0]);
            System.out.printf("    Legitimacy:     %3d → %3d  (%+d)%n",
                    start[1], sf.getLegitimacy(), sf.getLegitimacy() - start[1]);

            // Territory presence & cohesion
            Map<String, int[]> startTerr = startTerritoryCohesion.get(id);
            for (IntrigueTerritory t : IntrigueServices.territories().getAll()) {
                int[] ts = startTerr.get(t.getTerritoryId());
                IntrigueTerritory.Presence startP = IntrigueTerritory.Presence.values()[ts[1]];
                IntrigueTerritory.Presence endP = t.getPresence(id);
                int startCoh = ts[0];
                int endCoh = t.getCohesion(id);
                System.out.printf("    Territory %-22s  presence: %-11s → %-11s  cohesion: %3d → %3d  (%+d)%n",
                        t.getName(), startP, endP, startCoh, endCoh, endCoh - startCoh);
            }

            // Op breakdown
            Map<String, Integer> counts = opCounts.get(id);
            Map<String, int[]> outcomes = outcomeCounts.get(id);
            if (counts.isEmpty()) {
                System.out.println("    Ops: (none)");
            } else {
                StringBuilder sb = new StringBuilder("    Ops: ");
                int total = 0;
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    if (total > 0) sb.append(", ");
                    String opType = e.getKey();
                    int count = e.getValue();
                    int[] oc = outcomes.getOrDefault(opType, new int[]{0, 0});
                    sb.append(opType).append("=").append(count);
                    sb.append(" (").append(oc[0]).append("W/").append(oc[1]).append("L)");
                    total += count;
                }
                sb.append("  (total=").append(total).append(")");
                System.out.println(sb);
            }

            // Dysfunction events
            int[] dys = dysfunctionCounts.get(id);
            int[] vr = vulnRaidCounts.get(id);
            boolean hasDys = dys[0] > 0 || dys[1] > 0 || dys[2] > 0;
            boolean hasVuln = vr[0] > 0 || vr[1] > 0;
            if (hasDys || hasVuln) {
                StringBuilder ev = new StringBuilder("    Events:");
                if (hasDys) {
                    ev.append(String.format(" %d infighting, %d expulsions, %d civil wars",
                            dys[0], dys[1], dys[2]));
                }
                if (hasVuln) {
                    if (hasDys) ev.append(" |");
                    ev.append(String.format(" vuln raids: %d launched, %d suffered", vr[0], vr[1]));
                }
                System.out.println(ev);
            }
            System.out.println();
        }

        System.out.printf("  Simulation: %d ticks x %.0f days = %.0f days (~%.1f cycles)%n",
                ticks, daysPerTick, ticks * daysPerTick, ticks * daysPerTick / 365f);

        // Player action summary
        if (playerEnabled) {
            System.out.println();
            System.out.printf("  Player Mode: %s (reconsiders every %d ticks)%n", playerMode, playerInterval);
            for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                String id = sf.getSubfactionId();
                int[] pa = playerActions.get(id);
                float mod = resolver.getSubfactionModifier(id);
                String modStr = mod == 0f ? "none" : String.format("%+.0f%%", mod * 100);
                System.out.printf("    %-35s  favors=%d  disfavors=%d  current modifier=%s%n",
                        sf.getName(), pa[0], pa[1], modStr);
            }
        }
    }

    static void testFullSimLoop() {
        test("Full sim loop: 50 ticks with subfaction logic", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

            int totalOps = 0;
            for (int t = 0; t < 50; t++) {
                for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                    for (int slot = 0; slot < OpEvaluator.MAX_CONCURRENT_OPS; slot++) {
                        IntrigueOp op = OpEvaluator.evaluate(sf, ops, "test");
                        if (op == null) break;
                        ops.startOp(op);
                        totalOps++;
                    }
                }
                clock.advanceDays(7);
                ops.advance(7f);
            }

            assertTrue("Ops happened", totalOps > 0);

            // All subfactions should still exist
            assertNotNull("sf_heg1", IntrigueServices.subfactions().getById("sf_heg1"));
            assertNotNull("sf_heg2", IntrigueServices.subfactions().getById("sf_heg2"));
            assertNotNull("sf_tri", IntrigueServices.subfactions().getById("sf_tri"));

            // All people should still exist
            assertNotNull("leader1", IntrigueServices.people().getById("leader1"));
            assertNotNull("leader2", IntrigueServices.people().getById("leader2"));
            assertNotNull("leader3", IntrigueServices.people().getById("leader3"));
        });
    }

    // ── Test harness ────────────────────────────────────────────────

    static void test(String name, Runnable body) {
        System.out.printf("  %-55s ", name + "...");
        try {
            body.run();
            System.out.println("✓ PASS");
            passed++;
        } catch (AssertionError e) {
            System.out.println("✗ FAIL: " + e.getMessage());
            failed++;
        } catch (Exception e) {
            System.out.println("✗ ERROR: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    static void assertTrue(String msg, boolean cond) {
        if (!cond) throw new AssertionError(msg + ": expected true");
    }

    static void assertFalse(String msg, boolean cond) {
        if (cond) throw new AssertionError(msg + ": expected false");
    }

    static void assertNotNull(String msg, Object obj) {
        if (obj == null) throw new AssertionError(msg + ": expected non-null");
    }

    static void assertNull(String msg, Object obj) {
        if (obj != null) throw new AssertionError(msg + ": expected null but got " + obj);
    }

    static void assertEquals(String msg, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + ": expected " + expected + " but got " + actual);
        }
    }
    /** Collapse all runs of whitespace to a single space for comparison. */
    static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
