package spinloki.Intrigue.territory;

/**
 * Types of locations where a subfaction base can be placed within a territory.
 * Stable locations are explicitly excluded — those are reserved for vanilla structures
 * (comm relays, nav buoys, sensor arrays).
 */
public enum BaseSlotType {
    /** Orbiting a planet (non-star). */
    PLANET_ORBIT,
    /** Embedded within an asteroid belt. */
    ASTEROID_BELT
}

