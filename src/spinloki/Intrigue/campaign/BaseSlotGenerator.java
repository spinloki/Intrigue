package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator.EntityLocation;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator.LocationType;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.util.*;
import java.util.logging.Logger;

/**
 * Generates {@link IntrigueTerritory.BaseSlot}s for a territory during bootstrap.
 *
 * <p>Scans all star systems in the territory's constellations, uses
 * {@link BaseThemeGenerator#getLocations} to find valid orbital positions
 * (asteroid belts/fields, ring systems, gas giant orbits, planet orbits, nebulae),
 * and selects well-spread locations up to the territory's capacity.</p>
 *
 * <p><b>Orbit parenting:</b> if a candidate location's orbit focus is a non-physical
 * feature (e.g. a ring system entity) that itself orbits something else (e.g. a star),
 * we walk up the orbit chain to find the real parent body and use its orbital radius.</p>
 */
public final class BaseSlotGenerator {

    private static final Logger log = Logger.getLogger(BaseSlotGenerator.class.getName());

    private BaseSlotGenerator() {}

    /**
     * Generate base slots for a territory.
     *
     * @param territory the territory to populate with slots
     * @param capacity  number of slots to create
     */
    public static void generateSlots(IntrigueTerritory territory, int capacity) {
        if (capacity <= 0) return;

        List<String> constellationNames = territory.getConstellationNames();
        if (constellationNames.isEmpty()) {
            log.warning("BaseSlotGenerator: territory '" + territory.getName()
                    + "' has no constellations; cannot generate slots.");
            return;
        }

        // Collect candidate systems
        Set<String> targetConstellations = new HashSet<>(constellationNames);
        List<StarSystemAPI> candidateSystems = new ArrayList<>();

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.hasTag(Tags.THEME_CORE) || sys.hasTag(Tags.THEME_CORE_POPULATED)) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            if (sys.getConstellation() == null) continue;
            if (!targetConstellations.contains(sys.getConstellation().getName())) continue;
            candidateSystems.add(sys);
        }

        if (candidateSystems.isEmpty()) {
            log.warning("BaseSlotGenerator: no candidate systems in territory '" + territory.getName() + "'.");
            return;
        }

        // Collect all candidate locations across all systems
        LinkedHashMap<LocationType, Float> weights = new LinkedHashMap<>();
        weights.put(LocationType.IN_ASTEROID_BELT, 10f);
        weights.put(LocationType.IN_ASTEROID_FIELD, 10f);
        weights.put(LocationType.IN_RING, 10f);
        weights.put(LocationType.GAS_GIANT_ORBIT, 10f);
        weights.put(LocationType.PLANET_ORBIT, 10f);
        weights.put(LocationType.IN_SMALL_NEBULA, 5f);

        Random rng = new Random();
        List<SlotCandidate> allCandidates = new ArrayList<>();

        for (StarSystemAPI sys : candidateSystems) {
            WeightedRandomPicker<EntityLocation> locs =
                    BaseThemeGenerator.getLocations(rng, sys, null, 100f, weights);

            // Extract all items from the picker
            while (!locs.isEmpty()) {
                EntityLocation loc = locs.pickAndRemove();
                if (loc == null) continue;

                SlotCandidate candidate = resolveCandidate(sys, loc);
                if (candidate != null) {
                    allCandidates.add(candidate);
                }
            }
        }

        if (allCandidates.isEmpty()) {
            log.warning("BaseSlotGenerator: no valid locations found in territory '" + territory.getName() + "'.");
            return;
        }

        // Select well-spread slots using greedy farthest-first across systems
        List<SlotCandidate> selected = selectSpreadSlots(allCandidates, capacity, candidateSystems.size());

        for (SlotCandidate sc : selected) {
            IntrigueTerritory.BaseSlot slot = new IntrigueTerritory.BaseSlot(
                    sc.systemId, sc.systemName, sc.orbitFocusEntityId,
                    sc.orbitRadius, sc.locationDescription);
            territory.addBaseSlot(slot);
        }

        log.info("BaseSlotGenerator: generated " + selected.size() + " base slots in territory '"
                + territory.getName() + "' (requested " + capacity + ").");
        for (IntrigueTerritory.BaseSlot slot : territory.getBaseSlots()) {
            log.info("  Slot: " + slot);
        }
    }

    /**
     * Resolve an EntityLocation into a SlotCandidate, walking up the orbit chain
     * to find a physical parent body if the location's focus is a non-physical feature.
     */
    private static SlotCandidate resolveCandidate(StarSystemAPI sys, EntityLocation loc) {
        if (loc.orbit == null) return null;

        SectorEntityToken focus = loc.orbit.getFocus();
        if (focus == null) {
            // Use system center as fallback
            focus = sys.getCenter();
            if (focus == null) return null;
        }

        // Estimate orbit radius from the focus entity's position relative to its parent
        // (EntityLocation's orbit was built by BaseThemeGenerator at a certain distance)
        float radius = estimateOrbitRadius(loc, focus);

        // Walk up the orbit chain: if the focus itself orbits something
        // (e.g. a ring system feature orbiting a star), use the parent instead
        // at the feature's orbital distance
        SectorEntityToken resolved = resolveOrbitFocus(focus);
        if (resolved != focus) {
            // The feature orbits something — use the parent at the ring/belt's distance
            radius = estimateEntityOrbitRadius(focus);
            focus = resolved;
        }

        String locDesc = describeLocationType(loc.type);

        return new SlotCandidate(
                sys.getId(), sys.getName(),
                focus.getId(), radius, locDesc);
    }

    /**
     * Estimate the orbital radius from an EntityLocation.
     * Uses the orbit's computed position relative to the focus.
     */
    private static float estimateOrbitRadius(EntityLocation loc, SectorEntityToken focus) {
        try {
            if (loc.orbit != null && loc.orbit.computeCurrentLocation() != null && focus != null) {
                float dx = loc.orbit.computeCurrentLocation().x - focus.getLocation().x;
                float dy = loc.orbit.computeCurrentLocation().y - focus.getLocation().y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 0) return dist;
            }
        } catch (Exception ignored) {}
        return 500f; // reasonable default
    }

    /**
     * Estimate an entity's orbit radius from its current position relative to its orbit focus.
     */
    private static float estimateEntityOrbitRadius(SectorEntityToken entity) {
        if (entity.getOrbit() != null && entity.getOrbit().getFocus() != null) {
            SectorEntityToken parent = entity.getOrbit().getFocus();
            float dx = entity.getLocation().x - parent.getLocation().x;
            float dy = entity.getLocation().y - parent.getLocation().y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > 0) return dist;
        }
        return 500f;
    }

    /**
     * Walk up the orbit chain to find the real physical body to orbit.
     * Stops when we reach an entity that doesn't orbit anything else,
     * or that is a star / planet (has mass).
     */
    private static SectorEntityToken resolveOrbitFocus(SectorEntityToken entity) {
        // Walk up at most 5 levels to avoid infinite loops
        SectorEntityToken current = entity;
        for (int i = 0; i < 5; i++) {
            if (current.getOrbit() == null || current.getOrbit().getFocus() == null) break;

            // If this entity is a star or planet, it's a valid orbit focus
            if (current.hasTag(Tags.STAR) || current.getMarket() != null) break;

            // Check if it's something like a ring band, belt, or other non-physical feature
            // that orbits a more substantial body
            SectorEntityToken parent = current.getOrbit().getFocus();
            if (parent == current) break; // self-referential, stop
            current = parent;
        }
        return current;
    }

    /**
     * Select up to `capacity` candidates that are well-spread across systems.
     * Prefers putting one slot per system before doubling up.
     */
    private static List<SlotCandidate> selectSpreadSlots(
            List<SlotCandidate> candidates, int capacity, int numSystems) {

        // Group candidates by system
        Map<String, List<SlotCandidate>> bySystem = new LinkedHashMap<>();
        for (SlotCandidate c : candidates) {
            bySystem.computeIfAbsent(c.systemId, k -> new ArrayList<>()).add(c);
        }

        // Shuffle within each system for variety
        Random rng = new Random();
        for (List<SlotCandidate> list : bySystem.values()) {
            Collections.shuffle(list, rng);
        }

        List<SlotCandidate> selected = new ArrayList<>();
        Set<String> usedSystems = new HashSet<>();

        // Pass 1: one slot per system (round-robin)
        List<String> systemIds = new ArrayList<>(bySystem.keySet());
        Collections.shuffle(systemIds, rng);
        for (String sysId : systemIds) {
            if (selected.size() >= capacity) break;
            List<SlotCandidate> sysCandidates = bySystem.get(sysId);
            if (!sysCandidates.isEmpty()) {
                selected.add(sysCandidates.remove(0));
                usedSystems.add(sysId);
            }
        }

        // Pass 2: fill remaining capacity from any system
        if (selected.size() < capacity) {
            for (String sysId : systemIds) {
                List<SlotCandidate> sysCandidates = bySystem.get(sysId);
                while (!sysCandidates.isEmpty() && selected.size() < capacity) {
                    selected.add(sysCandidates.remove(0));
                }
                if (selected.size() >= capacity) break;
            }
        }

        return selected;
    }

    private static String describeLocationType(LocationType type) {
        if (type == null) return "unknown";
        switch (type) {
            case IN_ASTEROID_BELT: return "asteroid belt";
            case IN_ASTEROID_FIELD: return "asteroid field";
            case IN_RING: return "ring system";
            case GAS_GIANT_ORBIT: return "gas giant orbit";
            case PLANET_ORBIT: return "planet orbit";
            case IN_SMALL_NEBULA: return "nebula";
            default: return type.name().toLowerCase();
        }
    }

    /** Intermediate data holder for slot candidates. */
    private static class SlotCandidate {
        final String systemId;
        final String systemName;
        final String orbitFocusEntityId;
        final float orbitRadius;
        final String locationDescription;

        SlotCandidate(String systemId, String systemName, String orbitFocusEntityId,
                      float orbitRadius, String locationDescription) {
            this.systemId = systemId;
            this.systemName = systemName;
            this.orbitFocusEntityId = orbitFocusEntityId;
            this.orbitRadius = orbitRadius;
            this.locationDescription = locationDescription;
        }
    }
}


