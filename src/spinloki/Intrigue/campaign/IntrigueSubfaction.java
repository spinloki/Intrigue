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

    private final String subfactionId;
    private final String name;       // display name (e.g. "Eventide Admiralty")
    private final String factionId;
    private String homeMarketId;

    private int power = 50;
    private String leaderId;
    private final List<String> memberIds = new ArrayList<>();

    private int relToPlayer = 0;
    private final Map<String, Integer> relToOthers = new HashMap<>();

    private long lastOpTimestamp = 0;

    /** Convenience constructor — uses subfactionId as name. */
    public IntrigueSubfaction(String subfactionId, String factionId, String homeMarketId) {
        this(subfactionId, subfactionId, factionId, homeMarketId);
    }

    /** Full constructor with display name. */
    public IntrigueSubfaction(String subfactionId, String name, String factionId, String homeMarketId) {
        this.subfactionId = subfactionId;
        this.name = name;
        this.factionId = factionId;
        this.homeMarketId = homeMarketId;
    }

    // ── Identity ────────────────────────────────────────────────────────

    public String getSubfactionId() { return subfactionId; }
    public String getName() { return name; }
    public String getFactionId() { return factionId; }

    public String getHomeMarketId() { return homeMarketId; }
    public void setHomeMarketId(String homeMarketId) { this.homeMarketId = homeMarketId; }

    /** Returns true if the subfaction has a home market. A homeless subfaction is dormant. */
    public boolean hasHomeMarket() { return homeMarketId != null && !homeMarketId.isEmpty(); }

    // ── Power ───────────────────────────────────────────────────────────

    public int getPower() { return power; }
    public void setPower(int power) { this.power = Math.max(0, Math.min(100, power)); }

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
}
