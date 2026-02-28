package spinloki.Intrigue.campaign;

import spinloki.Intrigue.config.TerritoryConfig;

import java.io.Serializable;
import java.util.*;

/**
 * A territory — a region of constellations outside the core worlds where
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
     * Per-subfaction presence level in this territory.
     * Key = subfactionId, Value = Presence enum.
     * A subfaction not in this map has Presence.NONE.
     */
    private final Map<String, Presence> subfactionPresence = new LinkedHashMap<>();

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

    /** Remove a subfaction from this territory entirely (cohesion and presence). */
    public void removeSubfaction(String subfactionId) {
        subfactionCohesion.remove(subfactionId);
        subfactionPresence.remove(subfactionId);
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

