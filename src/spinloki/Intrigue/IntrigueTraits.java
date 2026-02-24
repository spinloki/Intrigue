package spinloki.Intrigue;

import java.util.List;

public final class IntrigueTraits {
    private IntrigueTraits() {}

    public static final String HONOR_BOUND = "HONOR_BOUND";
    public static final String OPPORTUNIST = "OPPORTUNIST";
    public static final String PARANOID = "PARANOID";
    public static final String CHARISMATIC = "CHARISMATIC";
    public static final String MERCILESS = "MERCILESS";

    public static final List<String> ALL = List.of(
            HONOR_BOUND, OPPORTUNIST, PARANOID, CHARISMATIC, MERCILESS
    );
}