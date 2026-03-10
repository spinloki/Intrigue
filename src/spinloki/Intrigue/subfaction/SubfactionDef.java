package spinloki.Intrigue.subfaction;

/**
 * Immutable definition of a subfaction — pure identity data loaded from config.
 * The actual game faction (colors, doctrine, ships) is defined in the corresponding
 * {@code .faction} file under {@code data/world/factions/}.
 *
 * This class exists to map a subfaction's game faction ID to its parent faction ID,
 * so we can copy relationships and know which vanilla faction this subfaction is
 * affiliated with.
 */
public class SubfactionDef {

    /** The faction ID as registered in factions.csv (e.g. "intrigue_hegemony_expeditionary"). */
    public final String id;

    /** The vanilla parent faction ID (e.g. "hegemony"). */
    public final String parentFactionId;

    /** Display name for logging/debug (the .faction file has the canonical display name). */
    public final String name;

    public SubfactionDef(String id, String parentFactionId, String name) {
        this.id = id;
        this.parentFactionId = parentFactionId;
        this.name = name;
    }

    @Override
    public String toString() {
        return name + " [" + id + " -> " + parentFactionId + "]";
    }
}

