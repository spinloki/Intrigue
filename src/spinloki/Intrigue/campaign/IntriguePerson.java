package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;

import java.io.Serializable;
import java.util.*;

public class IntriguePerson implements Serializable {

    public enum LocationType {
        HOME,      // at home market (normal)
        MARKET,    // temporarily at a different market
        FLEET,     // assigned to a fleet
        UNKNOWN
    }

    private final String personId;
    private final String factionId;

    // Anchor: where they “return” to when not out doing something
    private String homeMarketId;

    // Current location (future-facing; you can mostly ignore until you implement travel)
    private LocationType locationType = LocationType.HOME;
    private String locationId = null; // marketId or fleetId depending on type

    // --- Mod-owned state ---
    private int power = 50;
    private int relToPlayer = 0;
    private final Set<String> traits = new LinkedHashSet<>();
    private final Map<String, Integer> relToOthers = new HashMap<>();

    // --- Operations state ---
    private long lastOpTimestamp = 0;

    public IntriguePerson(String personId, String factionId, String homeMarketId) {
        this.personId = personId;
        this.factionId = factionId;
        this.homeMarketId = homeMarketId;
        this.locationId = homeMarketId;
    }

    public String getPersonId() { return personId; }
    public String getFactionId() { return factionId; }

    public String getHomeMarketId() { return homeMarketId; }
    public void setHomeMarketId(String homeMarketId) {
        this.homeMarketId = homeMarketId;
        // If we’re “at home”, keep location in sync
        if (locationType == LocationType.HOME) {
            this.locationId = homeMarketId;
        }
    }

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

    public int getPower() { return power; }
    public void setPower(int power) { this.power = power; }

    public int getRelToPlayer() { return relToPlayer; }
    public void setRelToPlayer(int relToPlayer) { this.relToPlayer = relToPlayer; }

    public Set<String> getTraits() { return traits; }

    public Integer getRelTo(String otherPersonId) { return relToOthers.get(otherPersonId); }
    public Map<String, Integer> getRelToOthersView() { return Collections.unmodifiableMap(relToOthers); }
    void setRelToInternal(String otherPersonId, int value) { relToOthers.put(otherPersonId, value); }

    public long getLastOpTimestamp() { return lastOpTimestamp; }
    public void setLastOpTimestamp(long lastOpTimestamp) { this.lastOpTimestamp = lastOpTimestamp; }

    public PersonAPI resolvePerson() {
        return Global.getSector().getImportantPeople().getPerson(personId);
    }
}