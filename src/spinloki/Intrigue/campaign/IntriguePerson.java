package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;

import java.io.Serializable;

public class IntriguePerson implements Serializable {
    private final String personId;
    private final String factionId;
    private String marketId; // where they "live" for now

    public IntriguePerson(String personId, String factionId, String marketId) {
        this.personId = personId;
        this.factionId = factionId;
        this.marketId = marketId;
    }

    public String getPersonId() { return personId; }
    public String getFactionId() { return factionId; }
    public String getMarketId() { return marketId; }
    public void setMarketId(String marketId) { this.marketId = marketId; }

    public PersonAPI resolvePerson() {
        return Global.getSector().getImportantPeople().getPerson(personId);
    }
}