package spinloki.Intrigue;

public final class IntrigueIds {
    private IntrigueIds() {}

    public static final String PERSIST_MANAGER_KEY = "spinloki_intrigue_people_manager";
    public static final String PERSIST_SCRIPT_KEY  = "spinloki_intrigue_people_script";

    public static final String PERSON_ID_PREFIX = "intrigue_person_";
    public static final String PERSON_TAG = "intrigue_person";

    // Safe defaults; you can swap these to ids.Ranks constants later if you prefer.
    public static final String DEFAULT_RANK = "citizen";
    public static final String DEFAULT_POST = "agent";
}