package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;
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

    /** Rolling log of homelessness-check events (bootstrap + periodic resolution). */
    private final List<String> homelessLog = new ArrayList<>();
    private static final int MAX_HOMELESS_LOG = 200;

    private void addHomelessLog(String entry) {
        String timestamp = String.valueOf(Global.getSector().getClock().getTimestamp());
        homelessLog.add("[" + timestamp + "] " + entry);
        while (homelessLog.size() > MAX_HOMELESS_LOG) {
            homelessLog.remove(0);
        }
    }

    /** Returns an unmodifiable view of the homelessness audit log. */
    public List<String> getHomelessLog() {
        return Collections.unmodifiableList(homelessLog);
    }

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

        // Track which markets have been claimed by a subfaction during bootstrap,
        // so that dynamic resolution doesn't assign the same market twice.
        Set<String> claimedMarketIds = new HashSet<>();

        // First pass: collect all statically-assigned market IDs
        for (SubfactionConfig.SubfactionDef def : config.subfactions) {
            if (def.homeMarketId != null && !def.homeMarketId.isEmpty()) {
                claimedMarketIds.add(def.homeMarketId);
            }
        }

        for (SubfactionConfig.SubfactionDef def : config.subfactions) {
            // Validate faction exists
            FactionAPI faction = Global.getSector().getFaction(def.factionId);
            if (faction == null) {
                log.warning("Skipping subfaction " + def.subfactionId + ": faction '" + def.factionId + "' not found.");
                continue;
            }

            // Resolve home market: use configured value, or find the best unclaimed market
            String homeMarketId = def.homeMarketId;
            if (homeMarketId == null || homeMarketId.isEmpty()) {
                homeMarketId = resolveHomeMarket(def.factionId, claimedMarketIds);
                if (homeMarketId != null) {
                    String msg = "Bootstrap: dynamically assigned market '" + homeMarketId
                            + "' to " + def.subfactionId + " (" + def.name + ", faction=" + def.factionId + ")";
                    log.info(msg);
                    addHomelessLog(msg);
                } else {
                    String msg = "Bootstrap: no market available for " + def.subfactionId
                            + " (" + def.name + ", faction=" + def.factionId + ") — homeless (dormant)";
                    log.info(msg);
                    addHomelessLog(msg);
                }
            }

            MarketAPI market = null;
            if (homeMarketId != null) {
                market = Global.getSector().getEconomy().getMarket(homeMarketId);
                if (market == null) {
                    log.warning("Configured market '" + homeMarketId + "' not found for subfaction "
                            + def.subfactionId + " — bootstrapping as homeless.");
                    addHomelessLog("Bootstrap: configured market '" + homeMarketId
                            + "' not found for " + def.subfactionId + " — homeless");
                    homeMarketId = null;
                }
            }

            if (homeMarketId != null) {
                claimedMarketIds.add(homeMarketId);
            }

            IntrigueSubfaction.SubfactionType sfType = IntrigueSubfaction.SubfactionType.POLITICAL;
            if ("CRIMINAL".equalsIgnoreCase(def.type)) {
                sfType = IntrigueSubfaction.SubfactionType.CRIMINAL;
            }

            IntrigueSubfaction sf = new IntrigueSubfaction(def.subfactionId, def.name, def.factionId, homeMarketId, sfType);
            sf.setPower(def.power);

            for (SubfactionConfig.MemberDef mDef : def.members) {
                PersonAPI person = createPersonFromDef(mDef, faction, market);
                if (person == null) continue;

                IntriguePerson.Role role = IntriguePerson.Role.valueOf(mDef.role);
                IntriguePerson ip = new IntriguePerson(
                        person.getId(), def.factionId, homeMarketId,
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
                     + " faction=" + def.factionId + " market=" + homeMarketId
                     + " power=" + sf.getPower() + " members=" + sf.getMemberIds().size());
        }

        bootstrapped = true;
        log.info("Subfaction bootstrap complete. Total: " + subfactions.size());
    }

    // ── Dynamic home market resolution ────────────────────────────────

    /**
     * Find the best unclaimed market for a faction outside core worlds.
     *
     * Only considers markets in procedurally generated (non-core) star systems.
     * Core-world markets are never selected — factions like pirates and pathers
     * should operate from fringe bases, not established core worlds.
     *
     * Within the eligible pool, selects the largest market (highest
     * {@code getSize()}).  Market size directly affects fleet production
     * capacity, so bigger bases produce stronger defense fleets.
     *
     * @param factionId        the faction to find markets for
     * @param claimedMarketIds markets already assigned to another subfaction
     * @return the market ID of the best candidate, or null if none found
     */
    private String resolveHomeMarket(String factionId, Set<String> claimedMarketIds) {
        MarketAPI best = null;

        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m == null) continue;
            if (!factionId.equals(m.getFactionId())) continue;
            if (claimedMarketIds.contains(m.getId())) continue;
            if (m.isHidden()) continue;

            // Skip core-world markets entirely
            LocationAPI loc = m.getContainingLocation();
            if (loc instanceof StarSystemAPI) {
                StarSystemAPI sys = (StarSystemAPI) loc;
                if (sys.hasTag(Tags.THEME_CORE)
                        || sys.hasTag(Tags.THEME_CORE_POPULATED)
                        || sys.hasTag(Tags.THEME_CORE_UNPOPULATED)) {
                    continue;
                }
            }

            if (best == null || m.getSize() > best.getSize()) {
                best = m;
            }
        }

        if (best != null) {
            log.info("resolveHomeMarket(" + factionId + "): selected " + best.getId()
                    + " '" + best.getName() + "' size=" + best.getSize());
            return best.getId();
        }

        return null;
    }


    /**
     * Attempt to find homes for any homeless subfactions.
     *
     * Called periodically (e.g. monthly) by the pacer script. When a market is
     * found, the subfaction's homeMarketId is set and its members are placed
     * at the market.
     *
     * @return a human-readable summary of any changes, or null if nothing happened
     */
    public String resolveHomelessSubfactions() {
        Set<String> claimedMarketIds = new HashSet<>();
        for (IntrigueSubfaction sf : subfactions.values()) {
            if (sf.hasHomeMarket()) {
                claimedMarketIds.add(sf.getHomeMarketId());
            }
        }

        StringBuilder result = new StringBuilder();
        IntriguePeopleManager peopleMgr = IntriguePeopleManager.get();

        for (IntrigueSubfaction sf : subfactions.values()) {
            if (sf.hasHomeMarket()) continue;

            String marketId = resolveHomeMarket(sf.getFactionId(), claimedMarketIds);
            if (marketId == null) {
                String msg = "Resolve: still homeless — " + sf.getName()
                        + " [" + sf.getSubfactionId() + "] (faction=" + sf.getFactionId() + ")";
                log.info(msg);
                addHomelessLog(msg);
                continue;
            }

            MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
            if (market == null) continue;

            sf.setHomeMarketId(marketId);
            claimedMarketIds.add(marketId);

            // Place all members at the new home market
            for (String personId : sf.getAllPersonIds()) {
                PersonAPI person = Global.getSector().getImportantPeople().getPerson(personId);
                if (person != null) {
                    person.setMarket(market);
                    market.addPerson(person);
                    if (market.getCommDirectory() != null) {
                        market.getCommDirectory().addPerson(person);
                    }
                }

                IntriguePerson ip = peopleMgr.getById(personId);
                if (ip != null) {
                    ip.setHomeMarketId(marketId);
                }
            }

            String msg = "Resolve: " + sf.getName() + " [" + sf.getSubfactionId() + "]"
                    + " found home at " + market.getName() + " (" + marketId + ")"
                    + " size=" + market.getSize() + " faction=" + sf.getFactionId();
            log.info(msg);
            addHomelessLog(msg);
            result.append(msg).append("\n");
        }

        return result.length() > 0 ? result.toString() : null;
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
            person.setMarket(market); // null is OK — homeless subfaction members

            // Register with sector important people
            Global.getSector().getImportantPeople().addPerson(person);

            // Place at market (only if one exists)
            if (market != null) {
                market.addPerson(person);
                if (market.getCommDirectory() != null) {
                    market.getCommDirectory().addPerson(person);
                }
            }

            return person;

        } catch (Exception e) {
            log.severe("Failed to create person: " + mDef.firstName + " " + mDef.lastName + ": " + e.getMessage());
            return null;
        }
    }
}
