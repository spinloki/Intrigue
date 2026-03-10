package spinloki.Intrigue.territory;

import java.io.Serializable;

/**
 * Represents a valid location within a territory where a subfaction can establish a base.
 * Pre-computed at territory generation time and stored in {@link TerritoryManager}.
 *
 * <p>Slots are never at stable locations — those are reserved for vanilla structures.
 * Valid slot types are planet orbits and asteroid belts.</p>
 */
public class BaseSlot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Unique identifier for this slot (e.g. "intrigue_ashenveil_prime__crucible_orbit"). */
    private final String slotId;

    /** The star system this slot is in. */
    private final String systemId;

    /** Entity ID of the orbit focus (planet for PLANET_ORBIT, star for ASTEROID_BELT). */
    private final String orbitFocusEntityId;

    /** Orbital radius for the base station. */
    private final float orbitRadius;

    /** What kind of location this is. */
    private final BaseSlotType slotType;

    /** Human-readable label (e.g. "orbit of Crucible", "Crucible Ring belt"). */
    private final String label;

    /** Whether a subfaction has claimed this slot. */
    private boolean occupied;

    /** The subfaction that claimed this slot, or null. */
    private String occupiedBySubfactionId;

    /** Entity ID of the spawned station, if any. */
    private String stationEntityId;

    public BaseSlot(String slotId, String systemId, String orbitFocusEntityId,
                    float orbitRadius, BaseSlotType slotType, String label) {
        this.slotId = slotId;
        this.systemId = systemId;
        this.orbitFocusEntityId = orbitFocusEntityId;
        this.orbitRadius = orbitRadius;
        this.slotType = slotType;
        this.label = label;
        this.occupied = false;
        this.occupiedBySubfactionId = null;
        this.stationEntityId = null;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getSlotId() { return slotId; }
    public String getSystemId() { return systemId; }
    public String getOrbitFocusEntityId() { return orbitFocusEntityId; }
    public float getOrbitRadius() { return orbitRadius; }
    public BaseSlotType getSlotType() { return slotType; }
    public String getLabel() { return label; }
    public boolean isOccupied() { return occupied; }
    public String getOccupiedBySubfactionId() { return occupiedBySubfactionId; }
    public String getStationEntityId() { return stationEntityId; }

    // ── Slot claiming ────────────────────────────────────────────────────

    /**
     * Claim this slot for a subfaction.
     */
    public void claim(String subfactionId) {
        this.occupied = true;
        this.occupiedBySubfactionId = subfactionId;
    }

    /**
     * Record the entity ID of the station placed at this slot.
     */
    public void setStationEntityId(String entityId) {
        this.stationEntityId = entityId;
    }

    /**
     * Release this slot (e.g. if the base is destroyed).
     */
    public void release() {
        this.occupied = false;
        this.occupiedBySubfactionId = null;
        this.stationEntityId = null;
    }

    @Override
    public String toString() {
        String status = occupied
                ? "occupied by " + occupiedBySubfactionId
                : "available";
        return slotType + " @ " + label + " [" + slotId + "] (" + status + ")";
    }
}

