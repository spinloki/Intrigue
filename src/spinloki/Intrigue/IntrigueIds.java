package spinloki.Intrigue;

public final class IntrigueIds {
    private IntrigueIds() {}

    public static final String PERSIST_MANAGER_KEY = "spinloki_intrigue_people_manager";
    public static final String PERSIST_SCRIPT_KEY  = "spinloki_intrigue_people_script";

    public static final String PERSON_ID_PREFIX = "intrigue_person_";
    public static final String PERSON_TAG = "intrigue_person";
    public static final String SUBFACTION_ID_PREFIX = "intrigue_subfaction_";

    // Safe defaults; you can swap these to ids.Ranks constants later if you prefer.
    public static final String DEFAULT_RANK = "citizen";
    public static final String DEFAULT_POST = "agent";

    public static final String PERSIST_PACER_SCRIPT_KEY = "spinloki_intrigue_pacer_script";
    public static final String PERSIST_OPS_MANAGER_KEY = "spinloki_intrigue_ops_manager";
    public static final String PERSIST_SUBFACTION_MANAGER_KEY = "spinloki_intrigue_subfaction_manager";
    public static final String PERSIST_TERRITORY_MANAGER_KEY = "spinloki_intrigue_territory_manager";

    /** Custom intel tag — groups all Intrigue intel items under one tab. */
    public static final String INTEL_TAG_INTRIGUE = "Intrigue";
}