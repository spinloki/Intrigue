package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import org.apache.log4j.Logger;

import java.awt.*;

/**
 * Creates a single star system from a {@link TerritoryConfig.SystemDef},
 * positioning it in hyperspace relative to its parent territory.
 *
 * Leverages vanilla {@link StarSystemGenerator#addStableLocations(StarSystemAPI, int)}
 * for stable location generation.
 */
public class TerritoryGenerator {

    private static final Logger log = Global.getLogger(TerritoryGenerator.class);

    /** Tag applied to all territory systems for easy lookup. */
    public static final String TAG_TERRITORY = "intrigue_territory";
    /** Memory key storing the territory id on each system. */
    public static final String MEM_TERRITORY_ID = "$intrigue_territoryId";

    /**
     * Generate a star system from the given definition and place it in hyperspace.
     *
     * @param systemDef   The system blueprint from config.
     * @param hyperspaceX Hyperspace X position for the system.
     * @param hyperspaceY Hyperspace Y position for the system.
     * @param territoryId The owning territory's id (stored in system memory).
     * @return The created star system.
     */
    public static StarSystemAPI generate(TerritoryConfig.SystemDef systemDef,
                                         float hyperspaceX, float hyperspaceY,
                                         String territoryId) {
        log.info("Generating territory system: " + systemDef.name + " [" + systemDef.id + "]");

        // --- Create the system ---
        StarSystemAPI system = Global.getSector().createStarSystem(systemDef.name);
        system.setOptionalUniqueId(systemDef.id);
        system.addTag(TAG_TERRITORY);
        system.getMemoryWithoutUpdate().set(MEM_TERRITORY_ID, territoryId);
        system.setAge(systemDef.age);

        // --- Position in hyperspace ---
        system.getLocation().set(hyperspaceX, hyperspaceY);

        // --- Init the primary star ---
        PlanetAPI star = system.initStar(
                systemDef.id + "_star",
                systemDef.starType,
                systemDef.starRadius,
                systemDef.coronaRadius
        );

        // --- Add planets ---
        for (TerritoryConfig.PlanetDef planetDef : systemDef.planets) {
            addPlanet(system, star, planetDef);
        }

        // --- Add asteroid belts ---
        for (TerritoryConfig.AsteroidBeltDef beltDef : systemDef.asteroidBelts) {
            addAsteroidBelt(system, star, beltDef);
        }

        // --- Add stable locations (vanilla utility) ---
        if (systemDef.stableLocationCount > 0) {
            StarSystemGenerator.addStableLocations(system, systemDef.stableLocationCount);
        }

        // --- Auto-generate jump points ---
        system.autogenerateHyperspaceJumpPoints(true, true);

        log.info("  System created: " + systemDef.name +
                " with " + systemDef.planets.size() + " planets, " +
                systemDef.asteroidBelts.size() + " belts, " +
                systemDef.stableLocationCount + " stable locations");

        return system;
    }

    private static void addPlanet(StarSystemAPI system, PlanetAPI star,
                                  TerritoryConfig.PlanetDef def) {
        PlanetAPI planet = system.addPlanet(
                def.id,
                star,
                def.name,
                def.type,
                def.orbitAngle,
                def.radius,
                def.orbitRadius,
                def.orbitDays
        );

        // Apply conditions — each planet needs a market for conditions to live on.
        // addPlanet already creates a basic planet-condition market, so we can add directly.
        if (def.conditions != null) {
            for (String condition : def.conditions) {
                planet.getMarket().addCondition(condition);
            }
        }

        log.info("  Added planet: " + def.name + " [" + def.type + "] at orbit " + def.orbitRadius);
    }

    private static void addAsteroidBelt(StarSystemAPI system, PlanetAPI star,
                                        TerritoryConfig.AsteroidBeltDef def) {
        system.addAsteroidBelt(
                star,
                def.count,
                def.orbitRadius,
                def.width,
                def.orbitDaysMin,
                def.orbitDaysMax,
                Terrain.ASTEROID_BELT,
                def.name
        );

        // Add a visual ring band to make the belt look nicer
        system.addRingBand(star, "misc", "rings_dust0", 256f, 0,
                Color.white, 256f, def.orbitRadius, def.orbitDaysMin + 10);

        log.info("  Added asteroid belt: " + (def.name != null ? def.name : "unnamed") +
                " at orbit " + def.orbitRadius);
    }

}


