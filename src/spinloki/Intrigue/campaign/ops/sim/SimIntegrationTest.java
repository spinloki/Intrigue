package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.OpEvaluator;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.config.SubfactionConfig;

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
        testConfigJsonRoundTrip();
        testFullSimLoop();

        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);
        System.out.println("═══════════════════════════════════════════════════");
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
        sfHeg1.setPower(60);
        sfHeg1.setLeaderId("leader1");
        sfHeg1.getMemberIds().add("member1");

        IntrigueSubfaction sfHeg2 = new IntrigueSubfaction("sf_heg2", "14th Battlegroup Detachment", "hegemony", "heg_market_2");
        sfHeg2.setPower(45);
        sfHeg2.setLeaderId("leader2");

        IntrigueSubfaction sfTri = new IntrigueSubfaction("sf_tri", "Tri-Tachyon Capital Assurance", "tritachyon", "tri_market");
        sfTri.setPower(50);
        sfTri.setLeaderId("leader3");
        sfTri.getMemberIds().add("member2");

        subfactions.addSubfaction(sfHeg1);
        subfactions.addSubfaction(sfHeg2);
        subfactions.addSubfaction(sfTri);

        IntrigueServices.init(clock, people, ops, opFactory, subfactions);
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
        test("Same-faction subfactions can target each other", () -> {
            setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");
            IntrigueSubfaction sfHeg2 = IntrigueServices.subfactions().getById("sf_heg2");

            // sf_heg1 (hegemony, MERCILESS, power 60) should be able to target sf_heg2 (hegemony, power 45)
            IntrigueOp op = OpEvaluator.evaluate(sfHeg1, ops, "test");
            assertNotNull("Should create an op", op);

            // The target could be sf_heg2 or sf_tri — both are valid
            String targetSfId = op.getTargetSubfactionId();
            assertTrue("Target should be sf_heg2 or sf_tri",
                    "sf_heg2".equals(targetSfId) || "sf_tri".equals(targetSfId));
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
        test("Power shifts apply to subfactions, not people", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntrigueSubfaction sfHeg1 = IntrigueServices.subfactions().getById("sf_heg1");

            int powerBefore = sfHeg1.getPower();

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

            // Subfaction power should have changed
            int powerAfter = sfHeg1.getPower();
            assertTrue("Subfaction power changed after op", powerAfter != powerBefore);

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
            // Check target subfaction name also survived
            IntrigueSubfaction target = IntrigueServices.subfactions().getById(op.getTargetSubfactionId());
            assertNotNull("Target subfaction exists", target);
            assertTrue("Target has a name",
                    target.getName() != null && !target.getName().isEmpty());
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
            assertEquals("Subfaction count", 16, config1.subfactions.size());
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
