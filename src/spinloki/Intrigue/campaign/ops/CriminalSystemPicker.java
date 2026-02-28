package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Picks a star system for a criminal (pirate/pather) base.
 *
 * Scoring mirrors vanilla {@code PirateBaseManager.pickSystemForPirateBase()}
 * with adjustments for Intrigue's needs:
 *
 * <ul>
 *   <li>Excludes core worlds (THEME_CORE, THEME_CORE_POPULATED).</li>
 *   <li>Weights systems closer to the core more heavily - criminals want to prey
 *       on trade routes, not hide in deep space.</li>
 *   <li>Prefers systems with few or no salvageables - less player traffic means
 *       the base stays hidden longer.</li>
 *   <li>Skips systems with existing markets (already occupied).</li>
 *   <li>Skips hidden/special systems.</li>
 * </ul>
 */
public class CriminalSystemPicker implements SystemPicker {

    private static final Logger log = Logger.getLogger(CriminalSystemPicker.class.getName());

    /** Systems beyond this hyperspace distance are placed in a fallback picker. */
    private static final float FAR_THRESHOLD = 36000f;

    private transient Random rng;

    public CriminalSystemPicker() {
        this(new Random());
    }

    public CriminalSystemPicker(Random rng) {
        this.rng = rng;
    }

    private Random getRng() {
        if (rng == null) rng = new Random();
        return rng;
    }

    @Override
    public StarSystemAPI pick() {
        // Build set of system IDs that already have markets (colonies or bases)
        Set<String> occupiedSystemIds = new HashSet<>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.getContainingLocation() instanceof StarSystemAPI) {
                occupiedSystemIds.add(((StarSystemAPI) m.getContainingLocation()).getId());
            }
        }

        WeightedRandomPicker<StarSystemAPI> near = new WeightedRandomPicker<>(getRng());
        WeightedRandomPicker<StarSystemAPI> far = new WeightedRandomPicker<>(getRng());

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            // ── Hard filters ────────────────────────────────────────
            if (system.hasTag(Tags.THEME_CORE)
                    || system.hasTag(Tags.THEME_CORE_POPULATED)) {
                continue;
            }
            if (system.hasTag(Tags.THEME_HIDDEN)) continue;
            if (system.hasTag(Tags.THEME_SPECIAL)) continue;

            // Skip systems that already have markets (colonies or other bases)
            if (occupiedSystemIds.contains(system.getId())) continue;

            // ── Theme weight ────────────────────────────────────────
            float themeWeight = computeThemeWeight(system);
            if (themeWeight <= 0f) continue;

            // ── Salvageable penalty ─────────────────────────────────
            // Fewer salvageables → higher weight (less player traffic).
            int salvageCount = system.getEntitiesWithTag(Tags.SALVAGEABLE).size();
            float salvageMult;
            if (salvageCount == 0) {
                salvageMult = 2.0f;     // empty system - ideal
            } else if (salvageCount <= 3) {
                salvageMult = 1.0f;     // light salvage - fine
            } else if (salvageCount <= 8) {
                salvageMult = 0.4f;     // moderate - less desirable
            } else {
                salvageMult = 0.1f;     // heavily populated with salvage - avoid
            }

            // ── Distance weight ─────────────────────────────────────
            // Closer to the sector center (core worlds) → higher weight.
            // Criminals want proximity to trade, not deep-space isolation.
            float dist = system.getLocation().length();
            float distMult;
            if (dist < 10000f) {
                distMult = 3.0f;        // right on the doorstep
            } else if (dist < 20000f) {
                distMult = 2.0f;        // near the core
            } else if (dist < 30000f) {
                distMult = 1.0f;        // moderate distance
            } else {
                distMult = 0.5f;        // deep fringe
            }

            float weight = themeWeight * salvageMult * distMult;
            if (weight <= 0f) continue;

            if (dist > FAR_THRESHOLD) {
                far.add(system, weight);
            } else {
                near.add(system, weight);
            }
        }

        // Prefer near systems; fall back to far if none available
        if (near.isEmpty()) {
            near.addAll(far);
        }

        StarSystemAPI picked = near.pick();
        if (picked != null) {
            log.info("CriminalSystemPicker: selected " + picked.getBaseName()
                    + " (dist=" + String.format("%.0f", picked.getLocation().length())
                    + ", salvageables=" + picked.getEntitiesWithTag(Tags.SALVAGEABLE).size() + ")");
        } else {
            log.warning("CriminalSystemPicker: no suitable system found.");
        }
        return picked;
    }

    /**
     * Theme-based weight, modeled after vanilla PirateBaseManager.
     * Core-unpopulated systems get a small weight (fringe core systems can work),
     * while misc/ruins/cleared-remnant systems are preferred.
     */
    private float computeThemeWeight(StarSystemAPI system) {
        if (system.hasTag(Tags.THEME_MISC_SKIP)) {
            return 1f;
        } else if (system.hasTag(Tags.THEME_MISC)) {
            return 3f;
        } else if (system.hasTag(Tags.THEME_REMNANT_NO_FLEETS)) {
            return 3f;
        } else if (system.hasTag(Tags.THEME_REMNANT_DESTROYED)) {
            return 3f;
        } else if (system.hasTag(Tags.THEME_RUINS)) {
            return 5f;
        } else if (system.hasTag(Tags.THEME_CORE_UNPOPULATED)) {
            return 1f;  // fringe core - risky but close to trade
        }
        return 0f;
    }
}






