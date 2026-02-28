package spinloki.Intrigue.campaign.spi;

import spinloki.Intrigue.campaign.IntrigueTerritory;

import java.util.Collection;

/**
 * Read/write access to the territory registry.
 *
 * In-game: implemented by IntrigueTerritoryManager.
 * In-sim: can be implemented by a SimTerritoryAccess.
 */
public interface IntrigueTerritoryAccess {

    /** Look up a territory by ID. Returns null if not found. */
    IntrigueTerritory getById(String territoryId);

    /** All managed territories. */
    Collection<IntrigueTerritory> getAll();

    /** Find the territory that contains a given constellation name. Returns null if not found. */
    IntrigueTerritory getTerritoryContaining(String constellationName);

    /** All territories where a given subfaction has presence (cohesion > 0). */
    Collection<IntrigueTerritory> getTerritoriesForSubfaction(String subfactionId);

    /** Per-tick decay rate for territory cohesion. */
    int getDecayPerTick();
}

