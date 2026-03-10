package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * Scans all star systems in a territory and selects a well-spread set of
 * {@link BaseSlot}s where subfaction bases can be placed.
 *
 * <p>The algorithm:</p>
 * <ol>
 *   <li><b>Discover all candidates</b> — scan every system for planet orbits and
 *       asteroid belts.</li>
 *   <li><b>Pin required slots</b> — any entity IDs listed in the territory's
 *       {@code pinnedBaseSlots} config are always included.</li>
 *   <li><b>Spread selection</b> — fill remaining capacity by round-robin across
 *       systems (1 slot per system before doubling up), picking randomly within
 *       each system.</li>
 *   <li><b>Cap</b> — total slots ≤ {@code baseSlotCount} from config (defaults to
 *       the number of systems if unset).</li>
 * </ol>
 *
 * <p>Stable locations are explicitly excluded.</p>
 */
public class BaseSlotGenerator {

    private static final Logger log = Global.getLogger(BaseSlotGenerator.class);

    /** Offset from the planet's surface for orbital bases. */
    private static final float PLANET_ORBIT_OFFSET = 200f;

    /**
     * Generate base slots for a territory, using the territory def for slot count and pinning.
     *
     * @param territoryDef The territory config (carries baseSlotCount and pinnedBaseSlots).
     * @param systemIds    IDs of all star systems in the territory (procgen + hand-crafted).
     * @return Selected base slots, spread across systems.
     */
    public static List<BaseSlot> generateSlots(TerritoryConfig.TerritoryDef territoryDef,
                                               List<String> systemIds) {
        String territoryId = territoryDef.id;

        // --- Step 1: Discover all candidates, grouped by system ---
        // Note: Global.getSector().getStarSystem(id) looks up by NAME, not by ID.
        // We must iterate all systems and match by getId() to find procgen systems reliably.
        Map<String, StarSystemAPI> systemLookup = buildSystemLookup(systemIds);

        Map<String, List<BaseSlot>> candidatesBySystem = new LinkedHashMap<>();
        for (String systemId : systemIds) {
            StarSystemAPI system = systemLookup.get(systemId);
            if (system == null) {
                log.warn("  BaseSlotGenerator: system '" + systemId + "' not found in sector — skipping");
                continue;
            }
            List<BaseSlot> systemCandidates = new ArrayList<>();
            scanPlanets(system, systemCandidates);
            scanAsteroidBelts(system, systemCandidates);
            if (systemCandidates.isEmpty()) {
                log.info("  BaseSlotGenerator: system '" + system.getBaseName() +
                        "' [" + systemId + "] has no candidate slots");
            }
            candidatesBySystem.put(systemId, systemCandidates);
        }

        int totalCandidates = 0;
        for (List<BaseSlot> list : candidatesBySystem.values()) {
            totalCandidates += list.size();
        }

        // --- Step 2: Determine capacity ---
        int capacity = territoryDef.baseSlotCount;
        if (capacity <= 0) {
            // Default: 1 slot per system in the constellation
            capacity = systemIds.size();
        }

        log.info("  BaseSlotGenerator [" + territoryId + "]: " + totalCandidates +
                " candidates across " + candidatesBySystem.size() + " systems, capacity=" + capacity);

        // --- Step 3: Pin required slots ---
        List<BaseSlot> selected = new ArrayList<>();
        Set<String> selectedSlotIds = new HashSet<>();
        // Track which systems already have a pinned slot
        Set<String> systemsWithSlot = new HashSet<>();

        for (String pinnedEntityId : territoryDef.pinnedBaseSlots) {
            BaseSlot pinned = findCandidateByEntityId(candidatesBySystem, pinnedEntityId);
            if (pinned != null) {
                selected.add(pinned);
                selectedSlotIds.add(pinned.getSlotId());
                systemsWithSlot.add(pinned.getSystemId());
                log.info("    Pinned slot: " + pinned);
            } else {
                log.warn("    Pinned entity '" + pinnedEntityId + "' not found in any system — skipping");
            }
        }

        // --- Step 4: Spread selection — round-robin across systems ---
        Random rand = new Random();

        // Build a list of systems that still have unpinned candidates
        List<String> systemOrder = new ArrayList<>();
        for (String sysId : candidatesBySystem.keySet()) {
            if (hasUnselectedCandidates(candidatesBySystem.get(sysId), selectedSlotIds)) {
                systemOrder.add(sysId);
            }
        }
        Collections.shuffle(systemOrder, rand);

        // Prioritize systems that don't have a slot yet
        List<String> withoutSlot = new ArrayList<>();
        List<String> withSlot = new ArrayList<>();
        for (String sysId : systemOrder) {
            if (systemsWithSlot.contains(sysId)) {
                withSlot.add(sysId);
            } else {
                withoutSlot.add(sysId);
            }
        }
        List<String> prioritized = new ArrayList<>();
        prioritized.addAll(withoutSlot);
        prioritized.addAll(withSlot);

        // Round-robin: keep cycling through systems picking one slot at a time
        while (selected.size() < capacity && !prioritized.isEmpty()) {
            Iterator<String> it = prioritized.iterator();
            boolean addedAny = false;

            while (it.hasNext() && selected.size() < capacity) {
                String sysId = it.next();
                List<BaseSlot> candidates = candidatesBySystem.get(sysId);
                BaseSlot pick = pickUnselected(candidates, selectedSlotIds, rand);

                if (pick != null) {
                    selected.add(pick);
                    selectedSlotIds.add(pick.getSlotId());
                    systemsWithSlot.add(pick.getSystemId());
                    addedAny = true;
                    log.info("    Spread-selected: " + pick);
                } else {
                    // This system has no more candidates — remove it
                    it.remove();
                }
            }

            if (!addedAny) break; // No progress, all systems exhausted
        }

        log.info("  BaseSlotGenerator [" + territoryId + "]: selected " + selected.size() +
                " slots (capacity=" + capacity + ", pinned=" + territoryDef.pinnedBaseSlots.size() + ")");
        for (BaseSlot slot : selected) {
            log.info("    " + slot);
        }

        return selected;
    }

    // ── Candidate discovery ──────────────────────────────────────────────

    /**
     * Create a PLANET_ORBIT candidate for each non-star planet in the system.
     */
    private static void scanPlanets(StarSystemAPI system, List<BaseSlot> candidates) {
        for (PlanetAPI planet : system.getPlanets()) {
            if (planet.isStar()) continue;

            String slotId = system.getId() + "__" + planet.getId() + "_orbit";
            float orbitRadius = planet.getRadius() + PLANET_ORBIT_OFFSET;

            candidates.add(new BaseSlot(
                    slotId,
                    system.getId(),
                    planet.getId(),
                    orbitRadius,
                    BaseSlotType.PLANET_ORBIT,
                    "orbit of " + planet.getName() + " in " + system.getBaseName()
            ));
        }
    }

    /**
     * Create an ASTEROID_BELT candidate for each asteroid belt terrain in the system.
     */
    private static void scanAsteroidBelts(StarSystemAPI system, List<BaseSlot> candidates) {
        int beltIndex = 0;
        for (SectorEntityToken terrain : system.getTerrainCopy()) {
            if (terrain.hasTag(Terrain.ASTEROID_BELT)) {
                String slotId = system.getId() + "__belt_" + beltIndex;

                SectorEntityToken orbitFocus = terrain.getOrbitFocus();
                String focusId = orbitFocus != null ? orbitFocus.getId() : system.getStar().getId();
                float orbitRadius = estimateOrbitRadius(terrain);

                String terrainName = terrain.getName();
                if (terrainName == null || terrainName.isEmpty()) {
                    terrainName = "asteroid belt #" + (beltIndex + 1);
                }

                candidates.add(new BaseSlot(
                        slotId,
                        system.getId(),
                        focusId,
                        orbitRadius,
                        BaseSlotType.ASTEROID_BELT,
                        terrainName + " in " + system.getBaseName()
                ));

                beltIndex++;
            }
        }
    }

    // ── Selection helpers ────────────────────────────────────────────────

    /**
     * Find a candidate whose orbit focus entity ID matches the pinned ID.
     * For PLANET_ORBIT slots, the orbit focus IS the planet, so we match against that.
     */
    private static BaseSlot findCandidateByEntityId(Map<String, List<BaseSlot>> candidatesBySystem,
                                                    String entityId) {
        for (List<BaseSlot> candidates : candidatesBySystem.values()) {
            for (BaseSlot slot : candidates) {
                if (slot.getOrbitFocusEntityId().equals(entityId)) {
                    return slot;
                }
            }
        }
        return null;
    }

    /**
     * Pick a random unselected candidate from this system's list.
     */
    private static BaseSlot pickUnselected(List<BaseSlot> candidates, Set<String> selectedIds,
                                           Random rand) {
        List<BaseSlot> available = new ArrayList<>();
        for (BaseSlot slot : candidates) {
            if (!selectedIds.contains(slot.getSlotId())) {
                available.add(slot);
            }
        }
        if (available.isEmpty()) return null;
        return available.get(rand.nextInt(available.size()));
    }

    /**
     * Check if a system's candidate list has any slots not yet selected.
     */
    private static boolean hasUnselectedCandidates(List<BaseSlot> candidates, Set<String> selectedIds) {
        for (BaseSlot slot : candidates) {
            if (!selectedIds.contains(slot.getSlotId())) return true;
        }
        return false;
    }

    /**
     * Estimate an entity's orbit radius from its position relative to its orbit focus.
     */
    private static float estimateOrbitRadius(SectorEntityToken entity) {
        if (entity.getOrbit() != null && entity.getOrbit().getFocus() != null) {
            SectorEntityToken focus = entity.getOrbit().getFocus();
            float dx = entity.getLocation().x - focus.getLocation().x;
            float dy = entity.getLocation().y - focus.getLocation().y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > 0) return dist;
        }
        float dist = entity.getLocation().length();
        return dist > 0 ? dist : 500f;
    }

    /**
     * Build a map from system ID to StarSystemAPI for the given IDs.
     * We can't use {@code Global.getSector().getStarSystem()} because that
     * method searches by <em>name</em>, not by ID. Procgen systems have
     * auto-generated IDs that don't match their display names, so a name-based
     * lookup silently fails for them.
     */
    private static Map<String, StarSystemAPI> buildSystemLookup(List<String> targetIds) {
        Set<String> idSet = new HashSet<>(targetIds);
        Map<String, StarSystemAPI> lookup = new LinkedHashMap<>();

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (idSet.contains(system.getId())) {
                lookup.put(system.getId(), system);
            }
        }

        if (lookup.size() < targetIds.size()) {
            log.warn("  BaseSlotGenerator: found " + lookup.size() + "/" + targetIds.size() +
                    " systems. Missing IDs:");
            for (String id : targetIds) {
                if (!lookup.containsKey(id)) {
                    log.warn("    missing: " + id);
                }
            }
        }

        return lookup;
    }
}



