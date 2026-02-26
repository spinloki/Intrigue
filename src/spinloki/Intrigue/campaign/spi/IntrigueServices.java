package spinloki.Intrigue.campaign.spi;

import spinloki.Intrigue.campaign.ops.OpFactory;

import java.util.logging.Logger;

/**
 * Static service locator for game-vs-sim swappable dependencies.
 *
 * Call {@link #init} at startup (game or sim) before any core logic runs.
 * Core logic accesses services via the static getters.
 */
public final class IntrigueServices {
    private static final Logger log = Logger.getLogger(IntrigueServices.class.getName());

    private static IntrigueClock clock;
    private static IntriguePeopleAccess people;
    private static IntrigueOpRunner ops;
    private static OpFactory opFactory;
    private static IntrigueSubfactionAccess subfactions;

    private IntrigueServices() {}

    /**
     * Wire up all services. Must be called before core logic executes.
     */
    public static void init(IntrigueClock clock, IntriguePeopleAccess people,
                            IntrigueOpRunner ops, OpFactory opFactory,
                            IntrigueSubfactionAccess subfactions) {
        IntrigueServices.clock = clock;
        IntrigueServices.people = people;
        IntrigueServices.ops = ops;
        IntrigueServices.opFactory = opFactory;
        IntrigueServices.subfactions = subfactions;
        log.info("IntrigueServices initialized: clock=" + clock.getClass().getSimpleName()
                + ", people=" + people.getClass().getSimpleName()
                + ", ops=" + ops.getClass().getSimpleName()
                + ", opFactory=" + opFactory.getClass().getSimpleName()
                + ", subfactions=" + subfactions.getClass().getSimpleName());
    }

    public static IntrigueClock clock() {
        if (clock == null) throw new IllegalStateException("IntrigueServices.clock not initialized. Call init() first.");
        return clock;
    }

    public static IntriguePeopleAccess people() {
        if (people == null) throw new IllegalStateException("IntrigueServices.people not initialized. Call init() first.");
        return people;
    }

    public static IntrigueOpRunner ops() {
        if (ops == null) throw new IllegalStateException("IntrigueServices.ops not initialized. Call init() first.");
        return ops;
    }

    public static OpFactory opFactory() {
        if (opFactory == null) throw new IllegalStateException("IntrigueServices.opFactory not initialized. Call init() first.");
        return opFactory;
    }

    public static IntrigueSubfactionAccess subfactions() {
        if (subfactions == null) throw new IllegalStateException("IntrigueServices.subfactions not initialized. Call init() first.");
        return subfactions;
    }

    /** Returns true if all services have been initialized. */
    public static boolean isInitialized() {
        return clock != null && people != null && ops != null && opFactory != null && subfactions != null;
    }

    /** Reset for testing (allows re-init with different implementations). */
    public static void reset() {
        clock = null;
        people = null;
        ops = null;
        opFactory = null;
        subfactions = null;
    }
}