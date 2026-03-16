package spinloki.Intrigue.territory;

/**
 * All recognized entanglement types. Each carries static metadata that
 * {@link TerritoryState} uses to derive hostility and decide op behavior.
 *
 * <p>Entanglements are territory-scoped, one per subfaction pair.
 * See the Entanglement Catalog design doc for full type definitions.</p>
 */
public enum EntanglementType {

    /** Weakened subfaction hires a normally-hostile subfaction for defense. */
    HIRED_PROTECTION(false, true),

    /** Subfaction A covertly backs subfaction B against a shared rival C. No hostility change on the A-B pair. */
    PROXY_SUPPORT(false, false),

    /** Two hostile subfactions temporarily cease fighting to counter a dominant shared enemy. */
    SHARED_ENEMY_PACT(false, true),

    /** Victim and third-party responder cooperate for a single punitive op against an aggressor. */
    RETALIATION_COALITION(false, true),

    /** Two subfactions in open, escalated conflict over territory dominance. */
    TERRITORIAL_WAR(true, false),

    /** Two same-parent subfactions in open conflict when both are FORTIFIED+. */
    CIVIL_WAR(true, false);

    /** Whether this entanglement makes the pair hostile (overrides baseline to hostile). */
    public final boolean setsHostile;

    /** Whether this entanglement suppresses baseline hostility (overrides to not-hostile). */
    public final boolean suppressesHostile;

    EntanglementType(boolean setsHostile, boolean suppressesHostile) {
        this.setsHostile = setsHostile;
        this.suppressesHostile = suppressesHostile;
    }
}
