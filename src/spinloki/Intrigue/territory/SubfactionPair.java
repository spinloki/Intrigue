package spinloki.Intrigue.territory;

import java.io.Serializable;

/**
 * Unordered pair of subfaction IDs, used as a map key for entanglements.
 * {@code SubfactionPair("a", "b")} equals {@code SubfactionPair("b", "a")}.
 *
 * <p>Pure Java — no Starsector imports.</p>
 */
public final class SubfactionPair implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The lexicographically smaller subfaction ID. */
    private final String first;

    /** The lexicographically larger subfaction ID. */
    private final String second;

    public SubfactionPair(String a, String b) {
        if (a.compareTo(b) <= 0) {
            this.first = a;
            this.second = b;
        } else {
            this.first = b;
            this.second = a;
        }
    }

    public String getFirst() { return first; }
    public String getSecond() { return second; }

    /** Returns true if the given subfaction ID is one of the two members. */
    public boolean contains(String subfactionId) {
        return first.equals(subfactionId) || second.equals(subfactionId);
    }

    /** Returns the other member of the pair, given one. */
    public String other(String subfactionId) {
        if (first.equals(subfactionId)) return second;
        if (second.equals(subfactionId)) return first;
        throw new IllegalArgumentException(subfactionId + " is not in pair (" + first + ", " + second + ")");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubfactionPair)) return false;
        SubfactionPair that = (SubfactionPair) o;
        return first.equals(that.first) && second.equals(that.second);
    }

    @Override
    public int hashCode() {
        return 31 * first.hashCode() + second.hashCode();
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }
}
