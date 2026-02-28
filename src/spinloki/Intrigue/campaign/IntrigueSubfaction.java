package spinloki.Intrigue.campaign;

import java.io.Serializable;
import java.util.*;

/**
 * A political subfaction within a game faction.
 *
 * Subfactions are the primary unit of political power in Intrigue.
 * Each has a leader (who initiates operations), members (who provide
 * narrative bonuses), and its own power level and relationships.
 *
 * Example: within the Hegemony, there might be an "Eventide Bloc"
 * and a "Chicomoztoc Guard", each vying for influence.
 */
public class IntrigueSubfaction implements Serializable {

    /** The kind of subfaction - controls which operations it can run. */
    public enum SubfactionType {
        /** Standard political subfaction: raids, diplomacy, etc. */
        POLITICAL,
        /** Criminal subfaction (pirates, pathers): can establish hidden bases outside core worlds. */
        CRIMINAL
    }

    private final String subfactionId;
    private final String name;       // display name (e.g. "Eventide Admiralty")
    private final String factionId;
    private String homeMarketId;
    private SubfactionType type = SubfactionType.POLITICAL;

    /**
     * When true, the subfaction's home market is not a valid raid target.
     * Defaults to true for CRIMINAL subfactions.
     */
    private boolean hidden = false;

    /**
     * Home cohesion - the subfaction's internal cohesion at its home market.
     * Territory-specific cohesion is tracked on IntrigueTerritory, not here.
     */
    private int homeCohesion = 50;
    private int legitimacy = 50;
    private String cohesionLabel = "Cohesion";
    private String legitimacyLabel = "Legitimacy";
    private String leaderId;
    private final List<String> memberIds = new ArrayList<>();

    private int relToPlayer = 0;
    private final Map<String, Integer> relToOthers = new HashMap<>();

    private long lastOpTimestamp = 0;

    /**
     * Number of consecutive ticks home cohesion has been below the civil war threshold.
     * After enough ticks, a Civil War op triggers.
     */
    private int lowHomeCohesionTicks = 0;

    /** Convenience constructor - uses subfactionId as name, defaults to POLITICAL. */
    public IntrigueSubfaction(String subfactionId, String factionId, String homeMarketId) {
        this(subfactionId, subfactionId, factionId, homeMarketId, SubfactionType.POLITICAL);
    }

    /** Constructor with display name, defaults to POLITICAL. */
    public IntrigueSubfaction(String subfactionId, String name, String factionId, String homeMarketId) {
        this(subfactionId, name, factionId, homeMarketId, SubfactionType.POLITICAL);
    }

    /** Full constructor with display name and type. */
    public IntrigueSubfaction(String subfactionId, String name, String factionId,
                              String homeMarketId, SubfactionType type) {
        this.subfactionId = subfactionId;
        this.name = name;
        this.factionId = factionId;
        this.homeMarketId = homeMarketId;
        this.type = type != null ? type : SubfactionType.POLITICAL;
        this.hidden = (this.type == SubfactionType.CRIMINAL);
    }

    // ── Identity ────────────────────────────────────────────────────────

    public String getSubfactionId() { return subfactionId; }
    public String getName() { return name; }
    public String getFactionId() { return factionId; }

    public SubfactionType getType() { return type; }
    public void setType(SubfactionType type) { this.type = type; }

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }

    public String getHomeMarketId() { return homeMarketId; }
    public void setHomeMarketId(String homeMarketId) { this.homeMarketId = homeMarketId; }

    /** Returns true if the subfaction has a home market. A homeless subfaction is dormant. */
    public boolean hasHomeMarket() { return homeMarketId != null && !homeMarketId.isEmpty(); }

    // ── Home Cohesion & Legitimacy ──────────────────────────────────────

    /** Home cohesion at the subfaction's base market (0–100). */
    public int getHomeCohesion() { return homeCohesion; }
    public void setHomeCohesion(int value) { this.homeCohesion = Math.max(0, Math.min(100, value)); }

    /** @deprecated Use getHomeCohesion() instead. Returns home cohesion for backward compat. */
    @Deprecated
    public int getCohesion() { return homeCohesion; }
    /** @deprecated Use setHomeCohesion() instead. Sets home cohesion for backward compat. */
    @Deprecated
    public void setCohesion(int cohesion) { setHomeCohesion(cohesion); }

    public int getLegitimacy() { return legitimacy; }
    public void setLegitimacy(int legitimacy) { this.legitimacy = Math.max(0, Math.min(100, legitimacy)); }

    /** Derived power: average of home cohesion and legitimacy (read-only). */
    public int getPower() { return (homeCohesion + legitimacy) / 2; }

    /** @deprecated Use setHomeCohesion / setLegitimacy instead. Sets both stats equally. */
    @Deprecated
    public void setPower(int power) {
        int clamped = Math.max(0, Math.min(100, power));
        this.homeCohesion = clamped;
        this.legitimacy = clamped;
    }

    public String getCohesionLabel() { return cohesionLabel; }
    public void setCohesionLabel(String label) { this.cohesionLabel = label != null ? label : "Cohesion"; }

    public String getLegitimacyLabel() { return legitimacyLabel; }
    public void setLegitimacyLabel(String label) { this.legitimacyLabel = label != null ? label : "Legitimacy"; }

    // ── Leadership ──────────────────────────────────────────────────────

    public String getLeaderId() { return leaderId; }
    public void setLeaderId(String leaderId) { this.leaderId = leaderId; }

    public List<String> getMemberIds() { return memberIds; }

    /** Returns all person IDs in this subfaction (leader + members). */
    public List<String> getAllPersonIds() {
        List<String> all = new ArrayList<>();
        if (leaderId != null) all.add(leaderId);
        all.addAll(memberIds);
        return all;
    }

    /** Check if a person is in this subfaction (as leader or member). */
    public boolean containsPerson(String personId) {
        if (personId == null) return false;
        if (personId.equals(leaderId)) return true;
        return memberIds.contains(personId);
    }

    // ── Relationships ───────────────────────────────────────────────────

    public int getRelToPlayer() { return relToPlayer; }
    public void setRelToPlayer(int relToPlayer) {
        this.relToPlayer = Math.max(-100, Math.min(100, relToPlayer));
    }

    public Integer getRelTo(String otherSubfactionId) { return relToOthers.get(otherSubfactionId); }
    public Map<String, Integer> getRelToOthersView() { return Collections.unmodifiableMap(relToOthers); }
    public void setRelToInternal(String otherSubfactionId, int value) {
        relToOthers.put(otherSubfactionId, Math.max(-100, Math.min(100, value)));
    }

    // ── Operations ──────────────────────────────────────────────────────

    public long getLastOpTimestamp() { return lastOpTimestamp; }
    public void setLastOpTimestamp(long lastOpTimestamp) { this.lastOpTimestamp = lastOpTimestamp; }

    public int getLowHomeCohesionTicks() { return lowHomeCohesionTicks; }
    public void setLowHomeCohesionTicks(int ticks) { this.lowHomeCohesionTicks = Math.max(0, ticks); }
    public void incrementLowHomeCohesionTicks() { this.lowHomeCohesionTicks++; }
    public void resetLowHomeCohesionTicks() { this.lowHomeCohesionTicks = 0; }
}
