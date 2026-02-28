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
        SimOpFactory opFactory = new SimOpFactory(new Random(42), SimConfig.defaults());
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
        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║         200-Tick Simulation Statistics            ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        SimConfig config = SimConfig.defaults();

        System.out.println("  Config:");
        System.out.printf("    Establish Base success:       %.0f%%%n", config.establishBaseSuccessProb * 100);
        System.out.printf("    Scout Territory success:      %.0f%%%n", config.scoutTerritorySuccessProb * 100);
        System.out.printf("    Establish Terr. Base success: %.0f%%%n", config.establishTerritoryBaseSuccessProb * 100);
        System.out.printf("    Patrol success:               %.0f%%%n", config.patrolSuccessProb * 100);
        System.out.printf("    Op cooldown:                  %.0f days%n", (float) OpEvaluator.COOLDOWN_DAYS);
        System.out.printf("    Min cohesion threshold:       %d%n", OpEvaluator.MIN_COHESION_THRESHOLD);
        System.out.println();

        SimClock clock = setupSim();
        SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

        int ticks = 200;
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
        for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
            opCounts.put(sf.getSubfactionId(), new LinkedHashMap<>());
            outcomeCounts.put(sf.getSubfactionId(), new LinkedHashMap<>());
        }

        // Track ops that need resolution checking
        List<IntrigueOp> pendingOps = new ArrayList<>();
        Map<String, String> opToSubfaction = new LinkedHashMap<>(); // opId -> sfId

        // Run
        for (int t = 0; t < ticks; t++) {
            for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                IntrigueOp op = OpEvaluator.evaluate(sf, ops, "sim");
                if (op != null) {
                    ops.startOp(op);
                    opCounts.get(sf.getSubfactionId())
                            .merge(op.getOpTypeName(), 1, Integer::sum);
                    pendingOps.add(op);
                    opToSubfaction.put(op.getOpId(), sf.getSubfactionId());
                }
            }
            clock.advanceDays(daysPerTick);
            ops.advance(daysPerTick);

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
            System.out.println();
        }

        System.out.printf("  Simulation: %d ticks x %.0f days = %.0f days (~%.1f cycles)%n",
                ticks, daysPerTick, ticks * daysPerTick, ticks * daysPerTick / 365f);
    }

    static void testFullSimLoop() {
        test("Full sim loop: 50 ticks with subfaction logic", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

            int totalOps = 0;
            for (int t = 0; t < 50; t++) {
                for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                    IntrigueOp op = OpEvaluator.evaluate(sf, ops, "test");
                    if (op != null) {
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
