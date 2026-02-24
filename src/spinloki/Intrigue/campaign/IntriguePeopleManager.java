package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import spinloki.Intrigue.IntrigueIds;

import java.io.Serializable;
import java.util.*;

public class IntriguePeopleManager implements Serializable {

    private final Map<String, IntriguePerson> people = new LinkedHashMap<>();
    private int nextId = 1;
    private boolean bootstrapped = false;

    public static IntriguePeopleManager get() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_MANAGER_KEY);

        if (existing instanceof IntriguePeopleManager) {
            return (IntriguePeopleManager) existing;
        }

        IntriguePeopleManager created = new IntriguePeopleManager();
        data.put(IntrigueIds.PERSIST_MANAGER_KEY, created);
        return created;
    }

    public Collection<IntriguePerson> getAll() {
        return Collections.unmodifiableCollection(people.values());
    }

    public void bootstrapIfNeeded() {
        if (bootstrapped) return;

        // Minimal default: 2 per faction that actually has markets.
        Map<String, List<MarketAPI>> marketsByFaction = getMarketsByFaction();
        Random rng = new Random(getStableSeed());

        for (Map.Entry<String, List<MarketAPI>> entry : marketsByFaction.entrySet()) {
            String factionId = entry.getKey();
            List<MarketAPI> markets = entry.getValue();
            if (markets.isEmpty()) continue;

            int count = 2;
            for (int i = 0; i < count; i++) {
                MarketAPI market = markets.get(i % markets.size());
                createAndPlacePerson(factionId, market, rng);
            }
        }

        bootstrapped = true;
    }

    public void refreshAll() {
        Map<String, MarketAPI> marketsById = indexMarketsById();

        for (IntriguePerson ip : people.values()) {
            MarketAPI market = marketsById.get(ip.getMarketId());
            if (market == null) continue;

            PersonAPI p = Global.getSector().getImportantPeople().getPerson(ip.getPersonId());
            if (p == null) {
                // Recreate if somehow lost
                p = recreatePerson(ip, market, new Random(getStableSeed()));
            }

            // Ensure market placement
            if (!containsPerson(market, p)) {
                market.addPerson(p);
            }

            // Ensure comm directory entry
            CommDirectoryAPI comm = market.getCommDirectory();
            if (comm != null) {
                // Avoid duplicates if API supports it; if not, repeated add is typically harmless.
                comm.addPerson(p);
            }
        }
    }

    private IntriguePerson createAndPlacePerson(String factionId, MarketAPI market, Random rng) {
        FactionAPI faction = Global.getSector().getFaction(factionId);
        if (faction == null) return null;

        PersonAPI p = faction.createRandomPerson();
        String id = IntrigueIds.PERSON_ID_PREFIX + (nextId++);
        p.setId(id);
        p.addTag(IntrigueIds.PERSON_TAG);

        p.setRankId(IntrigueIds.DEFAULT_RANK);
        p.setPostId(IntrigueIds.DEFAULT_POST);
        p.setImportanceAndVoice(PersonImportance.MEDIUM, rng);
        p.setMarket(market);

        Global.getSector().getImportantPeople().addPerson(p);

        market.addPerson(p);
        CommDirectoryAPI comm = market.getCommDirectory();
        if (comm != null) comm.addPerson(p);

        IntriguePerson ip = new IntriguePerson(id, factionId, market.getId());
        people.put(id, ip);
        return ip;
    }

    private PersonAPI recreatePerson(IntriguePerson ip, MarketAPI market, Random rng) {
        FactionAPI faction = Global.getSector().getFaction(ip.getFactionId());
        if (faction == null) return null;

        PersonAPI p = faction.createRandomPerson();
        p.setId(ip.getPersonId());
        p.addTag(IntrigueIds.PERSON_TAG);

        p.setRankId(IntrigueIds.DEFAULT_RANK);
        p.setPostId(IntrigueIds.DEFAULT_POST);
        p.setImportanceAndVoice(PersonImportance.MEDIUM, rng);
        p.setMarket(market);

        Global.getSector().getImportantPeople().addPerson(p);
        return p;
    }

    private Map<String, List<MarketAPI>> getMarketsByFaction() {
        Map<String, List<MarketAPI>> result = new HashMap<>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m == null) continue;
            String fid = m.getFactionId();
            if (fid == null) continue;
            if ("player".equals(fid) || "neutral".equals(fid)) continue;

            result.computeIfAbsent(fid, k -> new ArrayList<>()).add(m);
        }
        return result;
    }

    private Map<String, MarketAPI> indexMarketsById() {
        Map<String, MarketAPI> result = new HashMap<>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m != null) result.put(m.getId(), m);
        }
        return result;
    }

    private boolean containsPerson(MarketAPI market, PersonAPI person) {
        for (PersonAPI p : market.getPeopleCopy()) {
            if (p != null && person.getId().equals(p.getId())) return true;
        }
        return false;
    }

    private long getStableSeed() {
        // Good enough for prototype: seed from sector memory if you want stable across reloads later
        return 1337L;
    }
}