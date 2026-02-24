package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
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
            MarketAPI home = marketsById.get(ip.getHomeMarketId());
            if (home == null) continue;

            PersonAPI p = Global.getSector().getImportantPeople().getPerson(ip.getPersonId());
            if (p == null) {
                p = recreatePerson(ip, home, new Random(getStableSeed()));
                if (p == null) continue;
            }

            if (ip.isCheckedOut()) {
                // Away: ensure they don't still appear at home
                removeAllFromMarketAndComm(home, ip.getPersonId());
                
                if (ip.getLocationType() == IntriguePerson.LocationType.MARKET) {
                    MarketAPI dest = marketsById.get(ip.getLocationId());
                    if (dest != null) {
                        ensureSingleInMarket(dest, p);
                        ensureSingleInCommDirectory(dest, p);
                    }
                }
            } else {
                // Home: ensure present & deduped
                ensureSingleInMarket(home, p);
                ensureSingleInCommDirectory(home, p);
            }

            syncToPersonMemory(p, ip);
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

        ip.setPower(40 + rng.nextInt(41)); // 40..80
        ip.setRelToPlayer(rng.nextInt(21) - 10); // -10..10

        // 0-2 random traits for now
        int traitCount = rng.nextInt(3);
        for (int t = 0; t < traitCount; t++) {
            String trait = spinloki.Intrigue.IntrigueTraits.ALL.get(rng.nextInt(spinloki.Intrigue.IntrigueTraits.ALL.size()));
            ip.getTraits().add(trait);
        }

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

    public IntriguePerson getById(String personId) {
        return people.get(personId);
    }

    private int clampRel(int v) {
        return Math.max(-100, Math.min(100, v));
    }

    public void setRelationship(String aId, String bId, int value) {
        if (aId == null || bId == null) return;
        if (aId.equals(bId)) return;

        IntriguePerson a = people.get(aId);
        IntriguePerson b = people.get(bId);
        if (a == null || b == null) return;

        int v = clampRel(value);
        a.setRelToInternal(bId, v);
        b.setRelToInternal(aId, v);
    }

    public void setRelToPlayer(String personId, int value) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.setRelToPlayer(clampRel(value));
    }

    public void addTrait(String personId, String trait) {
        IntriguePerson ip = people.get(personId);
        if (ip == null || trait == null) return;
        ip.getTraits().add(trait.trim().toUpperCase());
    }

    public void removeTrait(String personId, String trait) {
        IntriguePerson ip = people.get(personId);
        if (ip == null || trait == null) return;
        ip.getTraits().remove(trait.trim().toUpperCase());
    }

    // Authority: IntriguePerson data in the manager is the source of truth.
    // Person memory ($intrigue_*) is a projection for rules/dialog/UI and may be overwritten any time.
    private void syncToPersonMemory(PersonAPI p, IntriguePerson ip) {
        p.getMemoryWithoutUpdate().set("$intrigue", true);
        p.getMemoryWithoutUpdate().set("$intrigue_id", ip.getPersonId());
        p.getMemoryWithoutUpdate().set("$intrigue_factionId", ip.getFactionId());
        p.getMemoryWithoutUpdate().set("$intrigue_homeMarketId", ip.getHomeMarketId());
        p.getMemoryWithoutUpdate().set("$intrigue_locType", ip.getLocationType());
        p.getMemoryWithoutUpdate().set("$intrigue_locId", ip.getLocationId());

        p.getMemoryWithoutUpdate().set("$intrigue_power", ip.getPower());
        p.getMemoryWithoutUpdate().set("$intrigue_relToPlayer", ip.getRelToPlayer());

        // Keep it easy for UI: one string for now
        p.getMemoryWithoutUpdate().set("$intrigue_traits", String.join(", ", ip.getTraits()));
    }

    private void ensureSingleInMarket(MarketAPI market, PersonAPI canonical) {
        String id = canonical.getId();

        List<PersonAPI> matches = new ArrayList<>();
        for (PersonAPI p : market.getPeopleCopy()) {
            if (p != null && id.equals(p.getId())) matches.add(p);
        }

        if (matches.isEmpty()) {
            market.addPerson(canonical);
            return;
        }

        boolean hasCanonicalInstance = false;
        for (PersonAPI p : matches) {
            if (p == canonical) { hasCanonicalInstance = true; break; }
        }

        if (!hasCanonicalInstance) {
            market.addPerson(canonical);
            matches.clear();
            for (PersonAPI p : market.getPeopleCopy()) {
                if (p != null && id.equals(p.getId())) matches.add(p);
            }
        }

        for (PersonAPI p : matches) {
            if (p != canonical) {
                market.removePerson(p);
            }
        }
    }

    private void ensureSingleInCommDirectory(MarketAPI market, PersonAPI canonical) {
        CommDirectoryAPI comm = market.getCommDirectory();
        if (comm == null) return; // MarketAPI.getCommDirectory() can be null unless inited.

        String id = canonical.getId();

        List<PersonAPI> matches = new ArrayList<>();
        for (CommDirectoryEntryAPI e : comm.getEntriesCopy()) {
            Object data = e.getEntryData();
            if (data instanceof PersonAPI) {
                PersonAPI p = (PersonAPI) data;
                if (id.equals(p.getId())) {
                    matches.add(p);
                }
            }
        }

        // None: add it
        if (matches.isEmpty()) {
            comm.addPerson(canonical);
            return;
        }

        // Exactly one and already canonical instance: good
        if (matches.size() == 1 && matches.get(0) == canonical) return;

        // Otherwise: remove every matching instance, then add canonical once
        for (PersonAPI p : matches) {
            comm.removePerson(p);
        }
        comm.addPerson(canonical);
    }

    public void syncMemory(String personId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;

        PersonAPI p = Global.getSector().getImportantPeople().getPerson(personId);
        if (p == null) return;

        syncToPersonMemory(p, ip);
    }

    private void removeAllFromMarketAndComm(MarketAPI market, String personId) {
        // Market people list
        for (PersonAPI p : new ArrayList<>(market.getPeopleCopy())) {
            if (p != null && personId.equals(p.getId())) {
                market.removePerson(p);
            }
        }

        // Comm directory
        CommDirectoryAPI comm = market.getCommDirectory();
        if (comm == null) return;

        for (CommDirectoryEntryAPI e : new ArrayList<>(comm.getEntriesCopy())) {
            Object data = e.getEntryData();
            if (data instanceof PersonAPI) {
                PersonAPI p = (PersonAPI) data;
                if (personId.equals(p.getId())) {
                    comm.removePerson(p);
                }
            }
        }
    }

    public void checkoutToFleet(String personId, String fleetId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.setOnFleet(fleetId);

        refreshAll();
        syncMemory(personId);
    }

    public void returnHome(String personId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.returnHome();

        refreshAll();
        syncMemory(personId);
    }

    public void checkoutToMarket(String personId, String marketId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.setAtMarket(marketId);
        refreshAll();
        syncMemory(personId);
    }
}