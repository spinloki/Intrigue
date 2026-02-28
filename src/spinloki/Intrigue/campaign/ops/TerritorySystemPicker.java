package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.util.*;
import java.util.logging.Logger;

/**
 * Picks a star system within a territory's constellations for base establishment.
 *
 * Iterates over all systems in the sector, selecting those whose constellation
 * name matches one of the territory's constellation names. Excludes core worlds,
 * hidden systems, and systems that already have a market.
 */
public class TerritorySystemPicker implements SystemPicker {

    private static final Logger log = Logger.getLogger(TerritorySystemPicker.class.getName());

    private final List<String> constellationNames;
    private transient Random rng;

    public TerritorySystemPicker(List<String> constellationNames) {
        this(constellationNames, new Random());
    }

    public TerritorySystemPicker(List<String> constellationNames, Random rng) {
        this.constellationNames = constellationNames != null ? new ArrayList<>(constellationNames) : new ArrayList<>();
        this.rng = rng;
    }

    private Random getRng() {
        if (rng == null) rng = new Random();
        return rng;
    }

    @Override
    public StarSystemAPI pick() {
        if (constellationNames.isEmpty()) {
            log.warning("TerritorySystemPicker: no constellation names configured.");
            return null;
        }

        Set<String> targetConstellations = new HashSet<>(constellationNames);
        WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<>(getRng());

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.hasTag(Tags.THEME_CORE) || sys.hasTag(Tags.THEME_CORE_POPULATED)) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;

            if (sys.getConstellation() == null) continue;
            if (!targetConstellations.contains(sys.getConstellation().getName())) continue;

            // Skip systems that already have markets
            boolean hasMarket = false;
            for (var entity : sys.getAllEntities()) {
                if (entity.getMarket() != null && !entity.getMarket().isPlanetConditionMarketOnly()) {
                    hasMarket = true;
                    break;
                }
            }
            if (hasMarket) continue;

            picker.add(sys, 1f);
        }

        StarSystemAPI picked = picker.pick();
        if (picked != null) {
            log.info("TerritorySystemPicker: picked " + picked.getBaseName()
                    + " (constellation: " + picked.getConstellation().getName() + ")");
        } else {
            log.warning("TerritorySystemPicker: no suitable system found in constellations: " + constellationNames);
        }
        return picked;
    }
}

