package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.config.SubfactionConfig;
import spinloki.Intrigue.config.SubfactionConfigLoader;
import spinloki.Intrigue.campaign.spi.IntrigueSubfactionAccess;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Game-side subfaction manager. Creates and persists subfactions
 * from the JSON config file (data/config/intrigue_subfactions.json).
 *
 * Stored in sector persistent data under PERSIST_SUBFACTION_MANAGER_KEY.
 */
public class IntrigueSubfactionManager implements Serializable, IntrigueSubfactionAccess {

    private static final Logger log = Logger.getLogger(IntrigueSubfactionManager.class.getName());

    private final Map<String, IntrigueSubfaction> subfactions = new LinkedHashMap<>();
    private boolean bootstrapped = false;
    private int nextPersonId = 1;

    // ── Singleton via persistent data ───────────────────────────────────

    public static IntrigueSubfactionManager get() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_SUBFACTION_MANAGER_KEY);

        if (existing instanceof IntrigueSubfactionManager) {
            return (IntrigueSubfactionManager) existing;
        }

        IntrigueSubfactionManager created = new IntrigueSubfactionManager();
        data.put(IntrigueIds.PERSIST_SUBFACTION_MANAGER_KEY, created);
        return created;
    }

    // ── IntrigueSubfactionAccess ────────────────────────────────────────

    @Override
    public IntrigueSubfaction getById(String subfactionId) {
        return subfactions.get(subfactionId);
    }

    @Override
    public Collection<IntrigueSubfaction> getAll() {
        return Collections.unmodifiableCollection(subfactions.values());
    }

    @Override
    public Collection<IntrigueSubfaction> getByFaction(String factionId) {
        if (factionId == null) return Collections.emptyList();
        List<IntrigueSubfaction> result = new ArrayList<>();
        for (IntrigueSubfaction sf : subfactions.values()) {
            if (factionId.equals(sf.getFactionId())) result.add(sf);
        }
        return result;
    }

    @Override
    public void setRelationship(String aId, String bId, int value) {
        if (aId == null || bId == null || aId.equals(bId)) return;
        IntrigueSubfaction a = subfactions.get(aId);
        IntrigueSubfaction b = subfactions.get(bId);
        if (a == null || b == null) return;
        int v = Math.max(-100, Math.min(100, value));
        a.setRelToInternal(bId, v);
        b.setRelToInternal(aId, v);
    }

    @Override
    public IntrigueSubfaction getSubfactionOf(String personId) {
        if (personId == null) return null;
        for (IntrigueSubfaction sf : subfactions.values()) {
            if (sf.containsPerson(personId)) return sf;
        }
        return null;
    }

    // ── Config-driven bootstrap ─────────────────────────────────────────

    /**
     * Bootstrap subfactions from the JSON config file.
     * Creates PersonAPI instances with the exact names, portraits, ranks, and traits
     * specified in the config, then wires them into subfactions.
     */
    public void bootstrapIfNeeded() {
        if (bootstrapped) return;

        SubfactionConfig config = SubfactionConfigLoader.load();
        if (config.subfactions == null || config.subfactions.isEmpty()) {
            log.warning("No subfaction definitions found in config; nothing to bootstrap.");
            bootstrapped = true;
            return;
        }

        IntriguePeopleManager peopleMgr = IntriguePeopleManager.get();

        for (SubfactionConfig.SubfactionDef def : config.subfactions) {
            // Validate faction exists
            FactionAPI faction = Global.getSector().getFaction(def.factionId);
            if (faction == null) {
                log.warning("Skipping subfaction " + def.subfactionId + ": faction '" + def.factionId + "' not found.");
                continue;
            }

            // Validate home market exists
            MarketAPI market = Global.getSector().getEconomy().getMarket(def.homeMarketId);
            if (market == null) {
                log.warning("Skipping subfaction " + def.subfactionId + ": market '" + def.homeMarketId + "' not found.");
                continue;
            }

            IntrigueSubfaction sf = new IntrigueSubfaction(def.subfactionId, def.name, def.factionId, def.homeMarketId);
            sf.setPower(def.power);

            for (SubfactionConfig.MemberDef mDef : def.members) {
                PersonAPI person = createPersonFromDef(mDef, faction, market);
                if (person == null) continue;

                IntriguePerson.Role role = IntriguePerson.Role.valueOf(mDef.role);
                IntriguePerson ip = new IntriguePerson(
                        person.getId(), def.factionId, def.homeMarketId,
                        def.subfactionId, role, mDef.bonus);

                if (mDef.traits != null) {
                    for (String trait : mDef.traits) {
                        ip.getTraits().add(trait);
                    }
                }

                peopleMgr.registerPerson(ip);

                if (role == IntriguePerson.Role.LEADER) {
                    sf.setLeaderId(person.getId());
                }
                sf.getMemberIds().add(person.getId());
            }

            if (sf.getLeaderId() == null) {
                log.warning("Subfaction " + def.subfactionId + " has no leader! Skipping.");
                continue;
            }

            subfactions.put(sf.getSubfactionId(), sf);
            log.info("Bootstrapped subfaction: " + def.name + " [" + def.subfactionId + "]"
                     + " faction=" + def.factionId + " market=" + def.homeMarketId
                     + " power=" + sf.getPower() + " members=" + sf.getMemberIds().size());
        }

        bootstrapped = true;
        log.info("Subfaction bootstrap complete. Total: " + subfactions.size());
    }

    // ── Person creation from config ─────────────────────────────────────

    private PersonAPI createPersonFromDef(SubfactionConfig.MemberDef mDef,
                                          FactionAPI faction, MarketAPI market) {
        try {
            PersonAPI person = faction.createRandomPerson();

            // Assign a unique intrigue ID
            String personId = IntrigueIds.PERSON_ID_PREFIX + (nextPersonId++);
            person.setId(personId);

            // Override name and appearance from config
            FullName.Gender gender = FullName.Gender.valueOf(mDef.gender);
            person.setName(new FullName(mDef.firstName, mDef.lastName, gender));
            person.setGender(gender);

            if (mDef.portraitId != null) {
                person.setPortraitSprite(mDef.portraitId);
            }

            if (mDef.rankId != null) {
                person.setRankId(mDef.rankId);
            }
            if (mDef.postId != null) {
                person.setPostId(mDef.postId);
            }

            person.addTag(IntrigueIds.PERSON_TAG);
            person.setFaction(faction.getId());
            person.setMarket(market);

            // Register with sector important people
            Global.getSector().getImportantPeople().addPerson(person);

            // Place at market
            market.addPerson(person);
            if (market.getCommDirectory() != null) {
                market.getCommDirectory().addPerson(person);
            }

            return person;

        } catch (Exception e) {
            log.severe("Failed to create person: " + mDef.firstName + " " + mDef.lastName + ": " + e.getMessage());
            return null;
        }
    }
}
