package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import com.fs.starfarer.api.impl.campaign.procgen.NameAssigner;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.lwjgl.util.vector.Vector2f;

import java.io.IOException;
import java.util.*;

/**
 * Top-level orchestrator for territory generation.
 * Finds existing "boring" vanilla constellations and co-opts them as territories.
 * Uses vanilla's {@link NameAssigner} to re-derive all system, star, and planet names
 * from the new territory name (e.g. "Alpha Ashenveil", "Alpha Ashenveil II").
 * Hand-crafted systems from config are then injected into the constellation.
 *
 * No systems are deleted — existing procgen content is preserved and renamed.
 *
 * Call {@link #generate()} from the mod plugin's {@code onNewGameAfterProcGen()}.
 */
public class TerritoryGenerationPlugin {

    private static final Logger log = Global.getLogger(TerritoryGenerationPlugin.class);

    /** Persistent data key for storing generated territory info for later milestones. */
    public static final String PERSISTENT_KEY = "intrigue_territories";

    /**
     * Tags that make a system too important to co-opt. Only the truly significant
     * content is excluded — minor ruins, scattered derelict probes, and misc filler
     * are fine to take over since they don't represent meaningful player content.
     */
    private static final Set<String> IMPORTANT_TAGS = new HashSet<>(Arrays.asList(
            Tags.THEME_INTERESTING,
            Tags.THEME_SPECIAL,
            Tags.THEME_CORE,
            Tags.THEME_CORE_POPULATED,
            Tags.THEME_CORE_UNPOPULATED,
            Tags.THEME_HIDDEN,
            Tags.THEME_REMNANT_MAIN,
            Tags.THEME_REMNANT_RESURGENT,
            Tags.THEME_REMNANT_SUPPRESSED,
            Tags.THEME_DERELICT_MOTHERSHIP,
            Tags.THEME_DERELICT_CRYOSLEEPER,
            Tags.THEME_RUINS_MAIN,
            Tags.HAS_CORONAL_TAP
    ));

    /**
     * Generate all territories defined in territories.json.
     */
    public static void generate() {
        log.info("=== Intrigue: Beginning territory generation ===");

        List<TerritoryConfig.TerritoryDef> territoryDefs;
        try {
            territoryDefs = TerritoryConfig.load();
        } catch (IOException | JSONException e) {
            log.error("Failed to load territory config!", e);
            return;
        }

        if (territoryDefs.isEmpty()) {
            log.warn("No territories defined in config — nothing to generate.");
            return;
        }

        // Find boring constellations to co-opt
        List<Constellation> boringConstellations = findBoringConstellations();
        log.info("  Found " + boringConstellations.size() + " boring constellations available");

        if (boringConstellations.isEmpty()) {
            log.error("  No boring constellations found — cannot create territories!");
            return;
        }

        // Shuffle so we pick different ones each run
        WeightedRandomPicker<Constellation> picker = new WeightedRandomPicker<>();
        for (Constellation c : boringConstellations) {
            picker.add(c);
        }

        Map<String, List<String>> generatedTerritories = new HashMap<>();

        for (TerritoryConfig.TerritoryDef territoryDef : territoryDefs) {
            Constellation picked = picker.pickAndRemove();
            if (picked == null) {
                log.error("  Ran out of boring constellations for territory '" +
                        territoryDef.name + "' — skipping!");
                continue;
            }

            List<String> systemIds = claimConstellation(territoryDef, picked);
            generatedTerritories.put(territoryDef.id, systemIds);
        }

        // Store in persistent data so later milestones can look up territory systems
        Global.getSector().getPersistentData().put(PERSISTENT_KEY, generatedTerritories);

        log.info("=== Intrigue: Territory generation complete. " +
                generatedTerritories.size() + "/" + territoryDefs.size() + " territories created. ===");
    }

    // ── Constellation selection ──────────────────────────────────────────

    /**
     * Find all constellations that have no interesting content — pure filler systems
     * that won't be missed if we co-opt them.
     */
    private static List<Constellation> findBoringConstellations() {
        Set<Constellation> allConstellations = new LinkedHashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            Constellation c = system.getConstellation();
            if (c != null) {
                allConstellations.add(c);
            }
        }

        log.info("  Total constellations in sector: " + allConstellations.size());

        List<Constellation> boring = new ArrayList<>();
        for (Constellation constellation : allConstellations) {
            String reason = getImportanceReason(constellation);
            if (reason == null) {
                boring.add(constellation);
            } else {
                log.info("  Skipping constellation '" + constellation.getNameWithType() +
                        "': " + reason);
            }
        }
        return boring;
    }

    /**
     * Returns a human-readable reason why a constellation is too important to co-opt,
     * or null if it's boring enough to take over.
     */
    private static String getImportanceReason(Constellation constellation) {
        List<StarSystemAPI> systems = constellation.getSystems();
        if (systems.size() < 2) return "too small (" + systems.size() + " systems)";

        for (StarSystemAPI system : systems) {
            for (String tag : system.getTags()) {
                if (IMPORTANT_TAGS.contains(tag)) {
                    return "system '" + system.getBaseName() + "' has tag " + tag;
                }
            }
            for (PlanetAPI planet : system.getPlanets()) {
                if (planet.getMarket() != null
                        && planet.getMarket().getFaction() != null
                        && !planet.getMarket().isPlanetConditionMarketOnly()
                        && planet.getMarket().getSize() > 0) {
                    return "system '" + system.getBaseName() + "' has populated market on " +
                            planet.getName();
                }
            }
        }
        return null;
    }

    // ── Core territory generation ────────────────────────────────────────

    /**
     * Co-opt an existing boring constellation as a territory:
     * 1. Rename it via {@link Constellation#setNameOverride(String)}
     * 2. Re-derive all system/star/planet names via {@link NameAssigner}
     * 3. Tag all systems
     * 4. Inject hand-crafted systems from config
     *
     * No systems are deleted — the existing procgen content is preserved with new names.
     */
    private static List<String> claimConstellation(TerritoryConfig.TerritoryDef def,
                                                   Constellation constellation) {
        String oldName = constellation.getNameWithType();
        log.info("Claiming constellation '" + oldName + "' as territory '" + def.name +
                "' [" + def.id + "] with " + constellation.getSystems().size() + " existing systems");

        // --- Step 1: Rename the constellation (map label) ---
        constellation.setNameOverride(def.name);

        // --- Step 2: Re-derive all system/star/planet names from the new name ---
        // NameAssigner traverses the constellation's systems and assigns structural names
        // (Alpha, Beta, etc.) and planet suffixes (I, II, III) based on the constellation name.
        // With renameSystem=true (the default), it also renames each system and its star.
        NameAssigner nameAssigner = new NameAssigner(constellation);
        nameAssigner.setSpecialNamesProbability(0f); // all names should derive from territory name
        nameAssigner.assignNames(def.name, null);

        log.info("  Renamed constellation and all systems to '" + def.name + "' derivatives");

        // --- Step 3: Tag all existing systems ---
        for (StarSystemAPI system : constellation.getSystems()) {
            system.addTag(TerritoryGenerator.TAG_TERRITORY);
            system.getMemoryWithoutUpdate().set(TerritoryGenerator.MEM_TERRITORY_ID, def.id);
        }

        // --- Step 4: Inject hand-crafted systems ---
        Vector2f center = constellation.getLocation();
        float offsetRadius = 1500f;

        for (int i = 0; i < def.systems.size(); i++) {
            TerritoryConfig.SystemDef systemDef = def.systems.get(i);

            float angle = (360f / def.systems.size()) * i;
            float rad = (float) Math.toRadians(angle);
            float sysX = center.x + (float) Math.cos(rad) * offsetRadius;
            float sysY = center.y + (float) Math.sin(rad) * offsetRadius;

            StarSystemAPI system = TerritoryGenerator.generate(systemDef, sysX, sysY, def.id);

            constellation.getSystems().add(system);
            system.setConstellation(constellation);

            log.info("  Injected hand-crafted system '" + systemDef.name + "'");
        }

        // --- Step 5: Clear hyperspace nebula for any newly added systems ---
        clearConstellationNebula(constellation);

        // Collect all system IDs
        List<String> systemIds = new ArrayList<>();
        for (StarSystemAPI system : constellation.getSystems()) {
            systemIds.add(system.getId());
        }

        log.info("  Territory '" + def.name + "' complete: " + systemIds.size() +
                " total systems (was '" + oldName + "')");
        return systemIds;
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Clears hyperspace nebula around the entire constellation area.
     */
    private static void clearConstellationNebula(Constellation constellation) {
        HyperspaceTerrainPlugin plugin = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
        NebulaEditor editor = new NebulaEditor(plugin);
        float minRadius = plugin.getTileSize() * 2f;

        for (StarSystemAPI system : constellation.getSystems()) {
            float radius = system.getMaxRadiusInHyperspace();
            editor.clearArc(system.getLocation().x, system.getLocation().y,
                    0, radius + minRadius * 0.5f, 0, 360f);
            editor.clearArc(system.getLocation().x, system.getLocation().y,
                    0, radius + minRadius, 0, 360f, 0.25f);
        }
    }
}
