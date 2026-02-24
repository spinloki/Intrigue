package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;

import java.io.Serializable;
import java.util.*;

public class IntriguePerson implements Serializable {
    private final String personId;
    private final String factionId;
    private String marketId;

    // --- Mod-owned state ---
    private int power = 50; // 0..100-ish for now
    private int relToPlayer = 0; // -100..100 (your system)
    private final Set<String> traits = new LinkedHashSet<>();
    private final Map<String, Integer> relToOthers = new HashMap<>(); // otherPersonId -> -100..100

    public IntriguePerson(String personId, String factionId, String marketId) {
        this.personId = personId;
        this.factionId = factionId;
        this.marketId = marketId;
    }

    public String getPersonId() { return personId; }
    public String getFactionId() { return factionId; }
    public String getMarketId() { return marketId; }
    public void setMarketId(String marketId) { this.marketId = marketId; }

    public int getPower() { return power; }
    public void setPower(int power) { this.power = power; }

    public int getRelToPlayer() { return relToPlayer; }
    public void setRelToPlayer(int relToPlayer) { this.relToPlayer = relToPlayer; }

    public Set<String> getTraits() { return traits; }

    public Integer getRelTo(String otherPersonId) {
        return relToOthers.get(otherPersonId);
    }

    public Map<String, Integer> getRelToOthersView() {
        return Collections.unmodifiableMap(relToOthers);
    }

    void setRelToInternal(String otherPersonId, int value) {
        relToOthers.put(otherPersonId, value);
    }

    public PersonAPI resolvePerson() {
        return Global.getSector().getImportantPeople().getPerson(personId);
    }
}