package spinloki.Intrigue.campaign;

import spinloki.Intrigue.config.TerritoryConfig;

import java.io.Serializable;
import java.util.*;

/**
 * A territory - a region of constellations outside the core worlds where
 * subfactions project power and pursue plot hooks.
 *
 * Cohesion is tracked per-subfaction per-territory: a subfaction operating
 * in a territory must maintain supply lines (convoys) or its cohesion there
 * decays, leading to infighting and inability to respond to threats.
 *
 * Constellation references are stored as names (Strings) because
 * {@link com.fs.starfarer.api.impl.campaign.procgen.Constellation} has
 * transient fields and isn't reliably serializable across saves.
 */
public class IntrigueTerritory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Presence level of a subfaction in a territory.
     * Progresses: NONE → SCOUTING → ESTABLISHED.
     */
    public enum Presence {
        /** No activity in this territory. */
        NONE,
        /** A scouting op is in progress or has completed; the subfaction is probing the region. */
        SCOUTING,
        /** A base has been established; full operations are unlocked. */
        ESTABLISHED
    }

    private final String territoryId;
    private String name;
    private TerritoryConfig.Tier tier;
    private String plotHook;
    private final List<String> constellationNames = new ArrayList<>();
    private final List<String> interestedFactions = new ArrayList<>();

    /**
     * Per-subfaction cohesion in this territory (0–100).
     * Key = subfactionId, Value = cohesion level.
     * Only meaningful when presence ≥ ESTABLISHED.
     */
    private final Map<String, Integer> subfactionCohesion = new LinkedHashMap<>();

    /**
     * Per-subfaction base market ID in this territory.
     * Key = subfactionId, Value = market ID of the base established by that subfaction.
     * Only set when presence = ESTABLISHED and a base has been created.
     */
    private final Map<String, String> subfactionBaseMarketId = new LinkedHashMap<>();

    /**
     * Per-subfaction presence level in this territory.
     * Key = subfactionId, Value = Presence enum.
     * A subfaction not in this map has Presence.NONE.
     */
    private final Map<String, Presence> subfactionPresence = new LinkedHashMap<>();

    /**
     * Per-subfaction tick counter for how many consecutive ticks territory
     * cohesion has been below the critical threshold (default 10).
     * Used to trigger expulsion after a sustained period of neglect.
     */
    private final Map<String, Integer> lowCohesionTicks = new LinkedHashMap<>();

    /**
     * Directed friction from one subfaction toward another in this territory (0–100).
     * Key = directed key "from|to", Value = friction level.
     * A→B and B→A are stored independently.
     */
    private final Map<String, Integer> pairFriction = new LinkedHashMap<>();

    /**
     * Pre-computed base slots within this territory. Each slot is a valid orbital
     * location where a subfaction can establish a base. The number of slots equals
     * the territory's capacity. Subfactions claim slots when starting an
     * establish-base op; unclaimed slots are available for new arrivals.
     */
    private final List<BaseSlot> baseSlots = new ArrayList<>();

    // ── BaseSlot inner class ────────────────────────────────────────────

    /**
     * A pre-computed orbital location within a territory where a base can be built.
     */
    public static class BaseSlot implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String systemId;
        private final String systemName;
        /** The entity to orbit (e.g. a star, planet). Resolved to avoid orbiting non-physical features. */
        private final String orbitFocusEntityId;
        private final float orbitRadius;
        private final String locationDescription; // e.g. "asteroid belt", "ring system", "planet orbit"

        private String claimedBySubfactionId;

        public BaseSlot(String systemId, String systemName, String orbitFocusEntityId,
                        float orbitRadius, String locationDescription) {
            this.systemId = systemId;
            this.systemName = systemName;
            this.orbitFocusEntityId = orbitFocusEntityId;
            this.orbitRadius = orbitRadius;
            this.locationDescription = locationDescription;
        }

        public String getSystemId() { return systemId; }
        public String getSystemName() { return systemName; }
        public String getOrbitFocusEntityId() { return orbitFocusEntityId; }
        public float getOrbitRadius() { return orbitRadius; }
        public String getLocationDescription() { return locationDescription; }

        public boolean isClaimed() { return claimedBySubfactionId != null; }
        public String getClaimedBySubfactionId() { return claimedBySubfactionId; }
        public void claim(String subfactionId) { this.claimedBySubfactionId = subfactionId; }
        public void release() { this.claimedBySubfactionId = null; }

        @Override
        public String toString() {
            return systemName + " (" + locationDescription + ")"
                    + (isClaimed() ? " [claimed by " + claimedBySubfactionId + "]" : " [free]");
        }
    }

    public IntrigueTerritory(String territoryId, String name, TerritoryConfig.Tier tier, String plotHook) {
        this.territoryId = territoryId;
        this.name = name;
        this.tier = tier != null ? tier : TerritoryConfig.Tier.LOW;
        this.plotHook = plotHook;
    }

    // ── Identity ────────────────────────────────────────────────────────

    public String getTerritoryId() { return territoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TerritoryConfig.Tier getTier() { return tier; }
    public void setTier(TerritoryConfig.Tier tier) { this.tier = tier; }

    public String getPlotHook() { return plotHook; }
    public void setPlotHook(String plotHook) { this.plotHook = plotHook; }

    // ── Constellations ──────────────────────────────────────────────────

    public List<String> getConstellationNames() {
        return Collections.unmodifiableList(constellationNames);
    }

    public void addConstellationName(String name) {
        if (name != null && !constellationNames.contains(name)) {
            constellationNames.add(name);
        }
    }

    // ── Interested factions ─────────────────────────────────────────────

    public List<String> getInterestedFactions() {
        return Collections.unmodifiableList(interestedFactions);
    }

    public void addInterestedFaction(String factionId) {
        if (factionId != null && !interestedFactions.contains(factionId)) {
            interestedFactions.add(factionId);
        }
    }

    public void removeInterestedFaction(String factionId) {
        interestedFactions.remove(factionId);
    }

    public boolean isFactionInterested(String factionId) {
        return interestedFactions.contains(factionId);
    }

    // ── Per-subfaction cohesion ─────────────────────────────────────────

    /**
     * Get a subfaction's cohesion in this territory.
     * Returns 0 if the subfaction has no presence here.
     */
    public int getCohesion(String subfactionId) {
        Integer val = subfactionCohesion.get(subfactionId);
        return val != null ? val : 0;
    }

    /**
     * Set a subfaction's cohesion in this territory (clamped 0–100).
     * Setting to a positive value establishes presence; setting to 0 or below
     * removes the subfaction from this territory.
     */
    public void setCohesion(String subfactionId, int value) {
        int clamped = Math.max(0, Math.min(100, value));
        if (clamped <= 0) {
            subfactionCohesion.remove(subfactionId);
        } else {
            subfactionCohesion.put(subfactionId, clamped);
        }
    }

    /** Returns true if the subfaction has any presence in this territory (SCOUTING or ESTABLISHED). */
    public boolean hasPresence(String subfactionId) {
        Presence p = subfactionPresence.get(subfactionId);
        return p != null && p != Presence.NONE;
    }

    // ── Per-subfaction base market ──────────────────────────────────────

    /**
     * Get the market ID of the base a subfaction has established in this territory.
     * Returns null if no base has been set.
     */
    public String getBaseMarketId(String subfactionId) {
        return subfactionBaseMarketId.get(subfactionId);
    }

    /**
     * Set (or clear) the market ID of a subfaction's base in this territory.
     * @param subfactionId the subfaction
     * @param marketId     the base market ID, or null to clear
     */
    public void setBaseMarketId(String subfactionId, String marketId) {
        if (marketId == null || marketId.isEmpty()) {
            subfactionBaseMarketId.remove(subfactionId);
        } else {
            subfactionBaseMarketId.put(subfactionId, marketId);
        }
    }

    /** Remove a subfaction from this territory entirely (cohesion, presence, base, base slot, low-cohesion counter, and friction). */
    public void removeSubfaction(String subfactionId) {
        subfactionCohesion.remove(subfactionId);
        subfactionPresence.remove(subfactionId);
        subfactionBaseMarketId.remove(subfactionId);
        lowCohesionTicks.remove(subfactionId);
        releaseSlot(subfactionId);
        // Remove all friction pairs involving this subfaction
        pairFriction.entrySet().removeIf(e -> {
            String[] parts = e.getKey().split("\\|");
            return parts[0].equals(subfactionId) || parts[1].equals(subfactionId);
        });
    }

    // ── Base slots ──────────────────────────────────────────────────────

    /** Get all base slots in this territory (unmodifiable view). */
    public List<BaseSlot> getBaseSlots() {
        return Collections.unmodifiableList(baseSlots);
    }

    /** Add a base slot during territory bootstrap. */
    public void addBaseSlot(BaseSlot slot) {
        if (slot != null) baseSlots.add(slot);
    }

    /** Get the number of base slots (= territory capacity). */
    public int getCapacity() {
        return baseSlots.size();
    }

    /** Get all unclaimed base slots. */
    public List<BaseSlot> getFreeSlots() {
        List<BaseSlot> free = new ArrayList<>();
        for (BaseSlot slot : baseSlots) {
            if (!slot.isClaimed()) free.add(slot);
        }
        return free;
    }

    /** Get the slot claimed by a specific subfaction, or null. */
    public BaseSlot getSlotClaimedBy(String subfactionId) {
        for (BaseSlot slot : baseSlots) {
            if (subfactionId.equals(slot.getClaimedBySubfactionId())) return slot;
        }
        return null;
    }

    /** Claim a specific slot for a subfaction. */
    public void claimSlot(BaseSlot slot, String subfactionId) {
        if (slot != null) slot.claim(subfactionId);
    }

    /** Release any slot claimed by a subfaction. */
    public void releaseSlot(String subfactionId) {
        for (BaseSlot slot : baseSlots) {
            if (subfactionId.equals(slot.getClaimedBySubfactionId())) {
                slot.release();
            }
        }
    }

    // ── Directed friction ─────────────────────────────────────────────

    /**
     * Get directed friction from one subfaction toward another in this territory (0–100).
     * Friction is asymmetric: A→B may differ from B→A. Returns 0 if no entry.
     */
    public int getFriction(String from, String to) {
        Integer val = pairFriction.get(directedKey(from, to));
        return val != null ? val : 0;
    }

    /**
     * Set directed friction from one subfaction toward another (clamped 0–100).
     * Setting to 0 removes the entry.
     */
    public void setFriction(String from, String to, int value) {
        int clamped = Math.max(0, Math.min(100, value));
        String key = directedKey(from, to);
        if (clamped <= 0) {
            pairFriction.remove(key);
        } else {
            pairFriction.put(key, clamped);
        }
    }

    /** Reset directed friction from one subfaction toward another to 0. */
    public void resetFriction(String from, String to) {
        pairFriction.remove(directedKey(from, to));
    }

    /**
     * Get all pairs of ESTABLISHED subfaction IDs in this territory.
     * Each pair is returned as a 2-element array [sfA, sfB] where sfA &lt; sfB lexicographically.
     */
    public List<String[]> getEstablishedPairs() {
        List<String> established = new ArrayList<>();
        for (Map.Entry<String, Presence> e : subfactionPresence.entrySet()) {
            if (e.getValue() == Presence.ESTABLISHED) {
                established.add(e.getKey());
            }
        }
        Collections.sort(established);
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < established.size(); i++) {
            for (int j = i + 1; j < established.size(); j++) {
                pairs.add(new String[]{established.get(i), established.get(j)});
            }
        }
        return pairs;
    }

    /** Number of subfactions with ESTABLISHED presence in this territory. */
    public int getEstablishedCount() {
        int count = 0;
        for (Presence p : subfactionPresence.values()) {
            if (p == Presence.ESTABLISHED) count++;
        }
        return count;
    }

    /** Unmodifiable view of all pairwise friction values. */
    public Map<String, Integer> getPairFrictionView() {
        return Collections.unmodifiableMap(pairFriction);
    }

    /** Directed key: "from|to" - order matters (A→B ≠ B→A). */
    private static String directedKey(String from, String to) {
        return from + "|" + to;
    }

    /** Get the number of consecutive ticks a subfaction's cohesion has been critically low. */
    public int getLowCohesionTicks(String subfactionId) {
        Integer val = lowCohesionTicks.get(subfactionId);
        return val != null ? val : 0;
    }

    /** Increment the low-cohesion tick counter for a subfaction. */
    public void incrementLowCohesionTicks(String subfactionId) {
        lowCohesionTicks.put(subfactionId, getLowCohesionTicks(subfactionId) + 1);
    }

    /** Reset the low-cohesion tick counter for a subfaction. */
    public void resetLowCohesionTicks(String subfactionId) {
        lowCohesionTicks.remove(subfactionId);
    }

    /** All subfaction IDs that have any presence (SCOUTING or ESTABLISHED) in this territory. */
    public Set<String> getActiveSubfactionIds() {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Presence> e : subfactionPresence.entrySet()) {
            if (e.getValue() != null && e.getValue() != Presence.NONE) {
                result.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Unmodifiable view of all per-subfaction cohesion values. */
    public Map<String, Integer> getSubfactionCohesionView() {
        return Collections.unmodifiableMap(subfactionCohesion);
    }

    // ── Per-subfaction presence ──────────────────────────────────────────

    /** Get a subfaction's presence level in this territory. Returns NONE if absent. */
    public Presence getPresence(String subfactionId) {
        Presence p = subfactionPresence.get(subfactionId);
        return p != null ? p : Presence.NONE;
    }

    /** Set a subfaction's presence level in this territory. Setting to NONE removes the entry. */
    public void setPresence(String subfactionId, Presence level) {
        if (level == null || level == Presence.NONE) {
            subfactionPresence.remove(subfactionId);
        } else {
            subfactionPresence.put(subfactionId, level);
        }
    }

    /** Unmodifiable view of all per-subfaction presence levels. */
    public Map<String, Presence> getSubfactionPresenceView() {
        return Collections.unmodifiableMap(subfactionPresence);
    }

    @Override
    public String toString() {
        return name + " [" + territoryId + "] tier=" + tier
                + " constellations=" + constellationNames.size()
                + " active=" + getActiveSubfactionIds().size();
    }
}

