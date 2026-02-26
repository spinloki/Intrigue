package spinloki.Intrigue.campaign;


import java.io.Serializable;
import java.util.*;

public class IntriguePerson implements Serializable {

    public enum LocationType {
        HOME,      // at home market (normal)
        MARKET,    // temporarily at a different market
        FLEET,     // assigned to a fleet
        UNKNOWN
    }

    public enum Role {
        LEADER,    // heads a subfaction, initiates ops
        MEMBER     // provides bonuses to the subfaction
    }

    private final String personId;
    private final String factionId;

    // Anchor: where they "return" to when not out doing something
    private String homeMarketId;

    // Subfaction membership
    private String subfactionId;
    private Role role = Role.MEMBER;
    private String bonus; // narrative description of what this person contributes

    // Current location (future-facing; you can mostly ignore until you implement travel)
    private LocationType locationType = LocationType.HOME;
    private String locationId = null; // marketId or fleetId depending on type

    // --- Mod-owned state ---
    private int relToPlayer = 0;
    private final Set<String> traits = new LinkedHashSet<>();
    private final Map<String, Integer> relToOthers = new HashMap<>();

    public IntriguePerson(String personId, String factionId, String homeMarketId) {
        this.personId = personId;
        this.factionId = factionId;
        this.homeMarketId = homeMarketId;
        this.locationId = homeMarketId;
    }

    /** Full constructor for config-driven creation. */
    public IntriguePerson(String personId, String factionId, String homeMarketId,
                          String subfactionId, Role role, String bonus) {
        this.personId = personId;
        this.factionId = factionId;
        this.homeMarketId = homeMarketId;
        this.locationId = homeMarketId;
        this.subfactionId = subfactionId;
        this.role = role;
        this.bonus = bonus;
    }

    public String getPersonId() { return personId; }
    public String getFactionId() { return factionId; }

    public String getHomeMarketId() { return homeMarketId; }
    public void setHomeMarketId(String homeMarketId) {
        this.homeMarketId = homeMarketId;
        if (locationType == LocationType.HOME) {
            this.locationId = homeMarketId;
        }
    }

    // ── Subfaction ──────────────────────────────────────────────────────

    public String getSubfactionId() { return subfactionId; }
    public void setSubfactionId(String subfactionId) { this.subfactionId = subfactionId; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isLeader() { return role == Role.LEADER; }

    public String getBonus() { return bonus; }
    public void setBonus(String bonus) { this.bonus = bonus; }

    // ── Location ────────────────────────────────────────────────────────

    public LocationType getLocationType() { return locationType; }
    public String getLocationId() { return locationId; }

    public boolean isCheckedOut() {
        return locationType != LocationType.HOME;
    }

    public void returnHome() {
        this.locationType = LocationType.HOME;
        this.locationId = homeMarketId;
    }

    public void setAtMarket(String marketId) {
        this.locationType = marketId.equals(homeMarketId) ? LocationType.HOME : LocationType.MARKET;
        this.locationId = marketId;
    }

    public void setOnFleet(String fleetId) {
        this.locationType = LocationType.FLEET;
        this.locationId = fleetId;
    }

    // ── Relationships & traits ──────────────────────────────────────────

    public int getRelToPlayer() { return relToPlayer; }
    public void setRelToPlayer(int relToPlayer) { this.relToPlayer = relToPlayer; }

    public Set<String> getTraits() { return traits; }

    public Integer getRelTo(String otherPersonId) { return relToOthers.get(otherPersonId); }
    public Map<String, Integer> getRelToOthersView() { return Collections.unmodifiableMap(relToOthers); }
    public void setRelToInternal(String otherPersonId, int value) { relToOthers.put(otherPersonId, value); }
}
