package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.OpEvaluator;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.*;

/**
 * Integration test that runs the REAL OpEvaluator and RaidOp code
 * against sim-side SPI implementations — proving the DI works.
 *
 * Run via: java SimIntegrationTest
 */
public class SimIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║     Intrigue DI Integration Tests                ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        testServicesInitialization();
        testOpEvaluatorUsesSimClock();
        testRealRaidOpRunsInSim();
        testPacerLogicRunsWithoutGame();
        testFullSimLoop();

        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);
        System.out.println("═══════════════════════════════════════════════════");
        System.exit(failed > 0 ? 1 : 0);
    }

    static SimClock setupSim() {
        IntrigueServices.reset();
        SimClock clock = new SimClock();
        SimPeopleAccess people = new SimPeopleAccess();
        SimOpRunner ops = new SimOpRunner();
        SimOpFactory opFactory = new SimOpFactory(new Random(42), SimConfig.defaults());

        // Create some people
        IntriguePerson p1 = new IntriguePerson("p1", "hegemony", "heg_market");
        p1.setPower(60);
        p1.getTraits().add(IntrigueTraits.MERCILESS);

        IntriguePerson p2 = new IntriguePerson("p2", "tritachyon", "tri_market");
        p2.setPower(40);

        IntriguePerson p3 = new IntriguePerson("p3", "pirates", "pir_market");
        p3.setPower(50);

        people.addPerson(p1);
        people.addPerson(p2);
        people.addPerson(p3);

        IntrigueServices.init(clock, people, ops, opFactory);
        return clock;
    }

    static void testServicesInitialization() {
        test("IntrigueServices initializes with sim implementations", () -> {
            setupSim();
            assertTrue("isInitialized", IntrigueServices.isInitialized());
            assertNotNull("clock", IntrigueServices.clock());
            assertNotNull("people", IntrigueServices.people());
            assertNotNull("ops", IntrigueServices.ops());
        });
    }

    static void testOpEvaluatorUsesSimClock() {
        test("OpEvaluator uses sim clock for cooldown", () -> {
            SimClock clock = setupSim();
            IntriguePerson p1 = IntrigueServices.people().getById("p1");

            // Set a recent op timestamp
            clock.advanceDays(10);
            p1.setLastOpTimestamp(clock.getTimestamp());
            clock.advanceDays(5); // only 5 days since last op

            // Should be on cooldown (30 days required)
            IntrigueOp op = OpEvaluator.evaluate(
                    p1,
                    IntrigueServices.people().getAll(),
                    IntrigueServices.ops(),
                    "test"
            );
            assertNull("Should be on cooldown", op);

            // Advance past cooldown
            clock.advanceDays(30);
            IntrigueOp op2 = OpEvaluator.evaluate(
                    p1,
                    IntrigueServices.people().getAll(),
                    IntrigueServices.ops(),
                    "test"
            );
            assertNotNull("Should produce an op after cooldown", op2);
        });
    }

    static void testRealRaidOpRunsInSim() {
        test("Real RaidOp lifecycle runs in sim", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();
            IntriguePerson p1 = IntrigueServices.people().getById("p1");
            IntriguePerson p2 = IntrigueServices.people().getById("p2");

            int p1PowerBefore = p1.getPower();
            int p2PowerBefore = p2.getPower();

            // Evaluate and start an op
            IntrigueOp op = OpEvaluator.evaluate(
                    p1,
                    IntrigueServices.people().getAll(),
                    ops,
                    "test"
            );
            assertNotNull("Should create an op", op);
            assertEquals("Op type", "Raid", op.getOpTypeName());

            ops.startOp(op);
            assertTrue("p1 checked out", p1.isCheckedOut());
            assertTrue("has active op", ops.hasActiveOp("p1"));

            // The RaidOp has TravelAndFightPhase which needs Starsector API to spawn fleets.
            // In sim mode, that phase will fail to spawn and mark itself as done with a loss.
            // Advance enough to resolve all phases.
            for (int i = 0; i < 100; i++) {
                clock.advanceDays(1);
                ops.advance(1f);
                if (op.isResolved()) break;
            }

            assertTrue("Op should resolve", op.isResolved());
            assertFalse("p1 should be back home", p1.isCheckedOut());
        });
    }

    static void testPacerLogicRunsWithoutGame() {
        test("Pacer-style tick runs without game", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

            Collection<IntriguePerson> all = IntrigueServices.people().getAll();
            int opsStarted = 0;

            // Simulate 20 weekly ticks
            for (int t = 0; t < 20; t++) {
                for (IntriguePerson ip : all) {
                    IntrigueOp op = OpEvaluator.evaluate(ip, all, ops, "test");
                    if (op != null) {
                        ops.startOp(op);
                        opsStarted++;
                    }
                }
                clock.advanceDays(7);
                ops.advance(7f);
            }

            assertTrue("At least one op should start", opsStarted > 0);
        });
    }

    static void testFullSimLoop() {
        test("Full sim loop: 50 ticks with real logic", () -> {
            SimClock clock = setupSim();
            SimOpRunner ops = (SimOpRunner) IntrigueServices.ops();

            int totalOps = 0;
            for (int t = 0; t < 50; t++) {
                Collection<IntriguePerson> all = IntrigueServices.people().getAll();
                for (IntriguePerson ip : new ArrayList<>(all)) {
                    IntrigueOp op = OpEvaluator.evaluate(ip, all, ops, "test");
                    if (op != null) {
                        ops.startOp(op);
                        totalOps++;
                    }
                }
                clock.advanceDays(7);
                ops.advance(7f);
            }

            // Verify the system ran without errors
            assertTrue("Ops happened", totalOps > 0);

            // All people should still exist
            assertNotNull("p1 exists", IntrigueServices.people().getById("p1"));
            assertNotNull("p2 exists", IntrigueServices.people().getById("p2"));
            assertNotNull("p3 exists", IntrigueServices.people().getById("p3"));
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
}


