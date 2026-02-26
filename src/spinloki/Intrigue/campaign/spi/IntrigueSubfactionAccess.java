package spinloki.Intrigue.campaign.spi;

import spinloki.Intrigue.campaign.IntrigueSubfaction;

import java.util.Collection;

/**
 * Read/write access to the subfaction registry.
 *
 * In-game: implemented by IntrigueSubfactionManager.
 * In-sim: implemented by SimSubfactionAccess.
 */
public interface IntrigueSubfactionAccess {

    /** Look up a subfaction by ID. Returns null if not found. */
    IntrigueSubfaction getById(String subfactionId);

    /** All managed subfactions. */
    Collection<IntrigueSubfaction> getAll();

    /** All subfactions belonging to a given game faction. */
    Collection<IntrigueSubfaction> getByFaction(String factionId);

    /** Set a bidirectional relationship between two subfactions. */
    void setRelationship(String aId, String bId, int value);

    /** Find the subfaction a person belongs to. Returns null if not found. */
    IntrigueSubfaction getSubfactionOf(String personId);
}
