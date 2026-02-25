package spinloki.Intrigue.campaign.spi;

import spinloki.Intrigue.campaign.IntriguePerson;

import java.util.Collection;

/**
 * Read/write access to the people registry.
 *
 * This is the interface that core logic (ops, evaluator, pacer) codes against
 * instead of reaching for IntriguePeopleManager.get() directly.
 *
 * In-game: implemented by IntriguePeopleManager.
 * In-sim: implemented by a lightweight stub holding SimPerson-backed data.
 */
public interface IntriguePeopleAccess {

    /** Look up a person by ID. Returns null if not found. */
    IntriguePerson getById(String personId);

    /** All managed people. */
    Collection<IntriguePerson> getAll();

    /** Set a bidirectional relationship between two people. */
    void setRelationship(String aId, String bId, int value);

    /** Mark a person as checked out to a market. */
    void checkoutToMarket(String personId, String marketId);

    /** Mark a person as checked out to a fleet. */
    void checkoutToFleet(String personId, String fleetId);

    /** Return a person to their home market. */
    void returnHome(String personId);

    /**
     * Sync person state to the game's UI/memory layer (e.g. PersonAPI memory keys).
     * In sim mode, this is a no-op.
     */
    void syncMemory(String personId);
}


