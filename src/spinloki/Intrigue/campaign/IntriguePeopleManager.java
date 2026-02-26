package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.spi.IntriguePeopleAccess;

import java.io.Serializable;
import java.util.*;

public class IntriguePeopleManager implements Serializable, IntriguePeopleAccess {

    private final Map<String, IntriguePerson> people = new LinkedHashMap<>();
    private int nextId = 1;
    private boolean bootstrapped = false;

    /**
     * Placement is the common interface for ensuring an Intrigue person's presence/absence in a location.
     * For now, MarketPlacement is fully implemented and FleetPlacement is a stub.
     *
     * NOTE: This is intentionally an internal detail of the manager; IntriguePerson remains pure data.
     */
    private interface Placement {
        IntriguePerson.LocationType type();
        String debugId();

        void ensurePresent(PersonAPI canonical);
        void ensureAbsent(String personId);
    }

    private final class MarketPlacement implements Placement {
        private final MarketAPI market;

        private MarketPlacement(MarketAPI market) {
            this.market = market;
        }

        @Override
        public IntriguePerson.LocationType type() {
            return IntriguePerson.LocationType.MARKET;
        }

        @Override
        public String debugId() {
            return market.getId();
        }

        @Override
        public void ensurePresent(PersonAPI canonical) {
            // Keep the PersonAPI's own market pointer aligned with where it is actually placed.
            canonical.setMarket(market);

            ensureSingleInMarket(market, canonical);
            ensureSingleInCommDirectory(market, canonical);
        }

        @Override
        public void ensureAbsent(String personId) {
            removeAllFromMarketAndComm(market, personId);
        }
    }

    /**
     * Stub for Milestone 2+.
     * For now, "placing into a fleet" means "not present in any market/comm directory".
     */
    private static final class FleetPlacement implements Placement {
        private final String fleetId;

        private FleetPlacement(String fleetId) {
            this.fleetId = fleetId;
        }

        @Override
        public IntriguePerson.LocationType type() {
            return IntriguePerson.LocationType.FLEET;
        }

        @Override
        public String debugId() {
            return fleetId;
        }

        @Override
        public void ensurePresent(PersonAPI canonical) {
            // TODO: Implement real fleet attachment (commander/officer/VIP) in Milestone 2.
        }

        @Override
        public void ensureAbsent(String personId) {
            // No-op until real fleet attachment is implemented.
        }
    }

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

    @Override
    public Collection<IntriguePerson> getAll() {
        return Collections.unmodifiableCollection(people.values());
    }

    @Override
    public IntriguePerson getById(String personId) {
        return people.get(personId);
    }

    public void bootstrapIfNeeded() {
        if (bootstrapped) return;
        // People creation is now driven by IntrigueSubfactionManager.bootstrapIfNeeded()
        bootstrapped = true;
    }

    /**
     * Create and place a person at a market. Used by IntrigueSubfactionManager during bootstrap.
     * Returns the IntriguePerson, or null on failure.
     */
    public IntriguePerson createPersonPublic(String factionId, MarketAPI market, Random rng) {
        return createAndPlacePerson(factionId, market, rng);
    }

    /**
     * Register an externally-created IntriguePerson and sync its memory.
     * Used by config-driven bootstrap.
     */
    public void registerPerson(IntriguePerson ip) {
        people.put(ip.getPersonId(), ip);

        PersonAPI p = Global.getSector().getImportantPeople().getPerson(ip.getPersonId());
        if (p != null) {
            syncToPersonMemory(p, ip);
        }
    }

    public void refreshAll() {
        Map<String, MarketAPI> marketsById = indexMarketsById();

        for (IntriguePerson ip : people.values()) {
            MarketAPI homeMarket = marketsById.get(ip.getHomeMarketId());
            if (homeMarket == null) continue;

            PersonAPI p = Global.getSector().getImportantPeople().getPerson(ip.getPersonId());
            if (p == null) {
                p = recreatePerson(ip, homeMarket, new Random(getStableSeed()));
                if (p == null) continue;
            }

            Placement home = new MarketPlacement(homeMarket);

            if (ip.isCheckedOut()) {
                // Away: ensure they do not appear at home
                home.ensureAbsent(ip.getPersonId());

                Placement current = resolveCurrentPlacement(ip, marketsById);
                if (current != null) {
                    current.ensurePresent(p);
                }
                else {
                    ip.returnHome();
                    home.ensurePresent(p);
                }
            } else {
                // Home: ensure present & deduped
                home.ensurePresent(p);
            }

            syncToPersonMemory(p, ip);
        }
    }

    private void refreshOne(IntriguePerson ip, Map<String, MarketAPI> marketsById) {
        MarketAPI homeMarket = marketsById.get(ip.getHomeMarketId());
        if (homeMarket == null) return;

        PersonAPI p = Global.getSector().getImportantPeople().getPerson(ip.getPersonId());
        if (p == null) {
            p = recreatePerson(ip, homeMarket, new Random(getStableSeed()));
            if (p == null) return;
        }

        Placement home = new MarketPlacement(homeMarket);

        if (ip.isCheckedOut()) {
            home.ensureAbsent(ip.getPersonId());   // enforce “not at home”
            Placement cur = resolveCurrentPlacement(ip, marketsById);
            if (cur != null) cur.ensurePresent(p); // place at destination
        } else {
            home.ensurePresent(p);                 // place/dedupe at home
        }

        syncToPersonMemory(p, ip);
    }

    /**
     * Ensure we remove the person from their *current* known placement before changing location fields.
     * This prevents "ghost" presence in an old market when locationId is overwritten.
     */
    private void unplaceFromCurrentLocation(IntriguePerson ip, Map<String, MarketAPI> marketsById) {
        Placement cur = resolveCurrentPlacement(ip, marketsById);
        if (cur == null) return;
        cur.ensureAbsent(ip.getPersonId());
    }

    private Placement resolveCurrentPlacement(IntriguePerson ip, Map<String, MarketAPI> marketsById) {
        IntriguePerson.LocationType t = ip.getLocationType();

        if (t == IntriguePerson.LocationType.HOME) {
            MarketAPI home = marketsById.get(ip.getHomeMarketId());
            return home != null ? new MarketPlacement(home) : null;
        }

        if (t == IntriguePerson.LocationType.MARKET) {
            MarketAPI m = marketsById.get(ip.getLocationId());
            return m != null ? new MarketPlacement(m) : null;
        }

        if (t == IntriguePerson.LocationType.FLEET) {
            return new FleetPlacement(ip.getLocationId());
        }

        return null;
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

        // Place through MarketPlacement so we get dedupe behavior immediately.
        new MarketPlacement(market).ensurePresent(p);

        IntriguePerson ip = new IntriguePerson(id, factionId, market.getId());

        ip.setRelToPlayer(rng.nextInt(21) - 10); // -10..10

        // 0-2 random traits for now
        int traitCount = rng.nextInt(3);
        for (int t = 0; t < traitCount; t++) {
            String trait = spinloki.Intrigue.IntrigueTraits.ALL.get(rng.nextInt(spinloki.Intrigue.IntrigueTraits.ALL.size()));
            ip.getTraits().add(trait);
        }

        people.put(id, ip);
        syncToPersonMemory(p, ip);
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
        Map<String, List<MarketAPI>> result = new HashMap<String, List<MarketAPI>>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m == null) continue;
            String fid = m.getFactionId();
            if (fid == null) continue;
            if ("player".equals(fid) || "neutral".equals(fid)) continue;

            List<MarketAPI> list = result.get(fid);
            if (list == null) {
                list = new ArrayList<MarketAPI>();
                result.put(fid, list);
            }
            list.add(m);
        }
        return result;
    }

    private Map<String, MarketAPI> indexMarketsById() {
        Map<String, MarketAPI> result = new HashMap<String, MarketAPI>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m != null) result.put(m.getId(), m);
        }
        return result;
    }

    private long getStableSeed() {
        // Good enough for prototype: seed from sector memory if you want stable across reloads later
        return 1337L;
    }

    private int clampRel(int v) {
        return Math.max(-100, Math.min(100, v));
    }

    public void setRelToPlayer(String personId, int value) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.setRelToPlayer(clampRel(value));
    }

    /**
     * Set a bidirectional relationship between two people.
     */
    public void setRelationship(String personIdA, String personIdB, int value) {
        if (personIdA == null || personIdB == null || personIdA.equals(personIdB)) return;
        IntriguePerson a = people.get(personIdA);
        IntriguePerson b = people.get(personIdB);
        if (a == null || b == null) return;
        int v = clampRel(value);
        a.setRelToInternal(personIdB, v);
        b.setRelToInternal(personIdA, v);
    }

    public void addTrait(String personId, String trait) {
        IntriguePerson ip = people.get(personId);
        if (ip == null || trait == null) return;
        ip.getTraits().add(trait.trim().toUpperCase(Locale.ROOT));
    }

    public void removeTrait(String personId, String trait) {
        IntriguePerson ip = people.get(personId);
        if (ip == null || trait == null) return;
        ip.getTraits().remove(trait.trim().toUpperCase(Locale.ROOT));
    }

    // Authority: IntriguePerson data in the manager is the source of truth.
    // Person memory ($intrigue_*) is a projection for rules/dialog/UI and may be overwritten any time.
    private void syncToPersonMemory(PersonAPI p, IntriguePerson ip) {
        p.getMemoryWithoutUpdate().set("$intrigue", true);
        p.getMemoryWithoutUpdate().set("$intrigue_id", ip.getPersonId());
        p.getMemoryWithoutUpdate().set("$intrigue_factionId", ip.getFactionId());
        p.getMemoryWithoutUpdate().set("$intrigue_homeMarketId", ip.getHomeMarketId());
        p.getMemoryWithoutUpdate().set("$intrigue_subfactionId", ip.getSubfactionId());
        p.getMemoryWithoutUpdate().set("$intrigue_role", ip.getRole().name());
        p.getMemoryWithoutUpdate().set("$intrigue_bonus", ip.getBonus());

        p.getMemoryWithoutUpdate().set("$intrigue_locType", ip.getLocationType().name());
        p.getMemoryWithoutUpdate().set("$intrigue_locId", ip.getLocationId());

        p.getMemoryWithoutUpdate().set("$intrigue_relToPlayer", ip.getRelToPlayer());

        // Keep it easy for UI: one string for now
        p.getMemoryWithoutUpdate().set("$intrigue_traits", String.join(", ", ip.getTraits()));
    }

    private void ensureSingleInMarket(MarketAPI market, PersonAPI canonical) {
        String id = canonical.getId();

        List<PersonAPI> matches = new ArrayList<PersonAPI>();
        for (PersonAPI p : market.getPeopleCopy()) {
            if (p != null && id.equals(p.getId())) matches.add(p);
        }

        if (matches.isEmpty()) {
            market.addPerson(canonical);
            return;
        }

        boolean hasCanonicalInstance = false;
        for (PersonAPI p : matches) {
            if (p == canonical) {
                hasCanonicalInstance = true;
                break;
            }
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

        List<PersonAPI> matches = new ArrayList<PersonAPI>();
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

        if (matches.size() == 1 && matches.get(0) == canonical) return;

        for (PersonAPI p : matches) {
            comm.removePerson(p);
        }
        comm.addPerson(canonical);
    }

    @Override
    public void syncMemory(String personId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;

        PersonAPI p = Global.getSector().getImportantPeople().getPerson(personId);
        if (p == null) return;

        syncToPersonMemory(p, ip);
    }

    private void removeAllFromMarketAndComm(MarketAPI market, String personId) {
        // Market people list
        for (PersonAPI p : new ArrayList<PersonAPI>(market.getPeopleCopy())) {
            if (p != null && personId.equals(p.getId())) {
                market.removePerson(p);
            }
        }

        // Comm directory
        CommDirectoryAPI comm = market.getCommDirectory();
        if (comm == null) return;

        for (CommDirectoryEntryAPI e : new ArrayList<CommDirectoryEntryAPI>(comm.getEntriesCopy())) {
            Object data = e.getEntryData();
            if (data instanceof PersonAPI) {
                PersonAPI p = (PersonAPI) data;
                if (personId.equals(p.getId())) {
                    comm.removePerson(p);
                }
            }
        }
    }

    @Override
    public void checkoutToFleet(String personId, String fleetId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;

        Map<String, MarketAPI> marketsById = indexMarketsById();
        unplaceFromCurrentLocation(ip, marketsById);

        ip.setOnFleet(fleetId);

        refreshOne(ip, marketsById);
    }

    @Override
    public void returnHome(String personId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;

        Map<String, MarketAPI> marketsById = indexMarketsById();
        if (ip.isCheckedOut()) {
            unplaceFromCurrentLocation(ip, marketsById);
        }

        ip.returnHome();

        refreshOne(ip, marketsById);
    }

    @Override
    public void checkoutToMarket(String personId, String marketId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;

        Map<String, MarketAPI> marketsById = indexMarketsById();
        unplaceFromCurrentLocation(ip, marketsById);

        ip.setAtMarket(marketId);

        refreshOne(ip, marketsById);
    }
}