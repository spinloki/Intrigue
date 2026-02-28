package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.intel.IntrigueTerritoryIntel;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;
import spinloki.Intrigue.config.TerritoryConfig;
import spinloki.Intrigue.config.TerritoryConfigLoader;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Game-side territory manager. Creates and persists territories from the JSON
 * config file (data/config/intrigue_territories.json).
 *
 * At bootstrap, randomly picks non-core constellations and assigns them
 * to territory definitions. Each territory tracks per-subfaction cohesion.
 *
 * Stored in sector persistent data under PERSIST_TERRITORY_MANAGER_KEY.
 */
public class IntrigueTerritoryManager implements Serializable, IntrigueTerritoryAccess {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(IntrigueTerritoryManager.class.getName());

    private final Map<String, IntrigueTerritory> territories = new LinkedHashMap<>();
    private boolean bootstrapped = false;
    private int decayPerTick = 2;

    // ── Singleton via persistent data ───────────────────────────────────

    public static IntrigueTerritoryManager get() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_TERRITORY_MANAGER_KEY);

        if (existing instanceof IntrigueTerritoryManager) {
            return (IntrigueTerritoryManager) existing;
        }

        IntrigueTerritoryManager created = new IntrigueTerritoryManager();
        data.put(IntrigueIds.PERSIST_TERRITORY_MANAGER_KEY, created);
        return created;
    }

    // ── IntrigueTerritoryAccess ─────────────────────────────────────────

    @Override
    public IntrigueTerritory getById(String territoryId) {
        return territories.get(territoryId);
    }

    @Override
    public Collection<IntrigueTerritory> getAll() {
        return Collections.unmodifiableCollection(territories.values());
    }

    @Override
    public IntrigueTerritory getTerritoryContaining(String constellationName) {
        if (constellationName == null) return null;
        for (IntrigueTerritory t : territories.values()) {
            if (t.getConstellationNames().contains(constellationName)) return t;
        }
        return null;
    }

    @Override
    public Collection<IntrigueTerritory> getTerritoriesForSubfaction(String subfactionId) {
        if (subfactionId == null) return Collections.emptyList();
        List<IntrigueTerritory> result = new ArrayList<>();
        for (IntrigueTerritory t : territories.values()) {
            if (t.hasPresence(subfactionId)) result.add(t);
        }
        return result;
    }

    @Override
    public int getDecayPerTick() {
        return decayPerTick;
    }

    // ── Bootstrap ───────────────────────────────────────────────────────

    /**
     * Bootstrap territories from config.
     *
     * Collects all non-core constellations from the sector, then randomly
     * assigns them to territory definitions. Each territory gets
     * numConstellations constellations from the pool.
     *
     * Also creates intel items for each territory.
     */
    public void bootstrapIfNeeded() {
        if (bootstrapped) return;

        TerritoryConfig config = TerritoryConfigLoader.load();
        if (config.territories == null || config.territories.isEmpty()) {
            log.warning("No territory definitions found in config; nothing to bootstrap.");
            bootstrapped = true;
            return;
        }

        decayPerTick = config.territoryDecayPerTick;

        // Collect all unique non-core constellations
        List<Constellation> availableConstellations = collectNonCoreConstellations();
        log.info("Territory bootstrap: " + availableConstellations.size() + " non-core constellations available.");

        if (availableConstellations.isEmpty()) {
            log.warning("No non-core constellations found; territories will be empty.");
        }

        // Shuffle for random assignment
        Collections.shuffle(availableConstellations, new Random());

        int poolIndex = 0;

        for (TerritoryConfig.TerritoryDef def : config.territories) {
            IntrigueTerritory territory = new IntrigueTerritory(
                    def.territoryId, def.name, def.tier, def.plotHook);

            // Add interested factions
            if (def.interestedFactions != null) {
                for (String factionId : def.interestedFactions) {
                    territory.addInterestedFaction(factionId);
                }
            }

            // Assign constellations from the pool
            int numToAssign = def.numConstellations;
            for (int i = 0; i < numToAssign && poolIndex < availableConstellations.size(); i++) {
                Constellation c = availableConstellations.get(poolIndex++);
                String cName = resolveConstellationName(c);
                territory.addConstellationName(cName);
                log.info("  Territory '" + def.name + "' ← constellation '" + cName + "'");
            }

            territories.put(territory.getTerritoryId(), territory);
            log.info("Bootstrapped territory: " + territory);

            // Generate base slots for this territory
            int capacity = def.getEffectiveCapacity();
            BaseSlotGenerator.generateSlots(territory, capacity);

            // Create and register intel item for this territory
            try {
                IntrigueTerritoryIntel intel = new IntrigueTerritoryIntel(territory);
                Global.getSector().getIntelManager().addIntel(intel, false);
                log.info("Created intel for territory: " + territory.getName());
            } catch (Exception e) {
                log.warning("Failed to create intel for territory '" + territory.getName() + "': " + e.getMessage());
            }
        }

        bootstrapped = true;
        log.info("Territory bootstrap complete. Total: " + territories.size()
                + " territories, " + poolIndex + " constellations assigned.");
    }

    // ── Constellation resolution ────────────────────────────────────────

    /**
     * Collect all unique non-core constellations from the sector.
     * A constellation is "non-core" if none of its systems have core-world tags.
     */
    private List<Constellation> collectNonCoreConstellations() {
        Set<Constellation> seen = new LinkedHashSet<>();

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (isCoreSystem(sys)) continue;

            Constellation c = sys.getConstellation();
            if (c == null) continue;

            // Only include the constellation if ALL its systems are non-core
            if (!isConstellationFullyNonCore(c)) continue;

            seen.add(c);
        }

        return new ArrayList<>(seen);
    }

    private boolean isConstellationFullyNonCore(Constellation c) {
        for (StarSystemAPI sys : c.getSystems()) {
            if (isCoreSystem(sys)) return false;
        }
        return true;
    }

    private boolean isCoreSystem(StarSystemAPI sys) {
        return sys.hasTag(Tags.THEME_CORE)
                || sys.hasTag(Tags.THEME_CORE_POPULATED)
                || sys.hasTag(Tags.THEME_CORE_UNPOPULATED);
    }

    /**
     * Resolve a stable name for a constellation. Prefers getName(), falls back
     * to getNameWithType(), then a synthetic name based on system names.
     */
    static String resolveConstellationName(Constellation c) {
        try {
            String name = c.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Exception ignored) {}

        try {
            String nameWithType = c.getNameWithType();
            if (nameWithType != null && !nameWithType.isEmpty()) return nameWithType;
        } catch (Exception ignored) {}

        // Fallback: use the first system's base name
        if (!c.getSystems().isEmpty()) {
            return c.getSystems().get(0).getBaseName() + " Region";
        }

        return "Unknown Constellation";
    }

    /**
     * Resolve a constellation name back to a live Constellation object.
     * Searches all star systems in the sector for a matching constellation.
     *
     * @param name the constellation name to resolve
     * @return the Constellation, or null if not found
     */
    public static Constellation resolveConstellationByName(String name) {
        if (name == null) return null;
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            Constellation c = sys.getConstellation();
            if (c == null) continue;
            if (name.equals(resolveConstellationName(c))) return c;
        }
        return null;
    }
}

