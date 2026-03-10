package spinloki.Intrigue.territory;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.io.Serializable;
import java.util.*;

/**
 * Per-territory brain that manages subfaction activity within a single territory.
 *
 * Implements {@link EveryFrameScript} so it ticks every frame (with day-based gating),
 * and {@link Serializable} so it persists across saves automatically when added via
 * {@code Global.getSector().addScript()}.
 *
 * <p>Each territory has exactly one TerritoryManager. It tracks which subfactions are
 * present and in what state ({@link SubfactionPresence}), and will drive state
 * transitions and spawning in later milestones.</p>
 *
 * <p>Milestone 2b scope: skeleton with presence tracking. advance() accumulates days
 * on each presence entry but does not trigger transitions yet.</p>
 */
public class TerritoryManager implements EveryFrameScript, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Global.getLogger(TerritoryManager.class);

    /** Persistent data key for the list of all TerritoryManagers. */
    public static final String PERSISTENT_KEY = "intrigue_territory_managers";

    private final String territoryId;
    private final List<String> systemIds;
    private final Map<String, SubfactionPresence> presences; // subfactionId → presence

    /** Accumulated fractional days since last day-tick processing. */
    private float dayAccumulator = 0f;

    /** Tracks whether we've logged the initial diagnostic message. */
    private transient boolean loggedInit = false;

    public TerritoryManager(String territoryId, List<String> systemIds) {
        this.territoryId = territoryId;
        this.systemIds = new ArrayList<>(systemIds);
        this.presences = new LinkedHashMap<>();
    }

    // ── Presence management ──────────────────────────────────────────────

    /**
     * Add a subfaction presence to this territory.
     */
    public void addPresence(String subfactionId, PresenceState initialState) {
        presences.put(subfactionId, new SubfactionPresence(subfactionId, initialState));
    }

    /**
     * Get a subfaction's presence, or null if not present.
     */
    public SubfactionPresence getPresence(String subfactionId) {
        return presences.get(subfactionId);
    }

    /**
     * Get all presences (unmodifiable view).
     */
    public Map<String, SubfactionPresence> getPresences() {
        return Collections.unmodifiableMap(presences);
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getTerritoryId() {
        return territoryId;
    }

    public List<String> getSystemIds() {
        return Collections.unmodifiableList(systemIds);
    }

    // ── EveryFrameScript ─────────────────────────────────────────────────

    @Override
    public void advance(float amount) {
        // One-time diagnostic log after load/creation
        if (!loggedInit) {
            log.info("TerritoryManager active: " + territoryId +
                    " with " + presences.size() + " subfactions in " +
                    systemIds.size() + " systems");
            loggedInit = true;
        }

        // Convert frame time to days and accumulate
        float days = Misc.getDays(amount);
        dayAccumulator += days;

        // Process once per day
        if (dayAccumulator < 1f) return;
        dayAccumulator -= 1f;

        // Advance day counters on all presences
        for (SubfactionPresence presence : presences.values()) {
            presence.advanceDays(1f);
        }

        // 2c will add state transition logic here
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    // ── Factory ──────────────────────────────────────────────────────────

    /**
     * Create TerritoryManagers for all territories and distribute subfactions across them.
     *
     * <p>Guarantees every subfaction appears in at least one territory before allowing
     * duplicates. Each territory gets 2–3 subfactions. The algorithm:</p>
     * <ol>
     *   <li>Round-robin deal: shuffle subfactions, assign one per territory in order
     *       until every subfaction has been placed at least once.</li>
     *   <li>Top-up: each territory that has fewer than its target count (2–3) gets
     *       additional random subfactions from the full pool (duplicates across
     *       territories are fine at this stage).</li>
     * </ol>
     */
    public static List<TerritoryManager> createForAllTerritories(
            Map<String, List<String>> territories,
            List<SubfactionDef> allDefs) {

        Random rand = new Random();
        List<String> territoryIds = new ArrayList<>(territories.keySet());

        // Create empty managers and decide target counts (2–3 each)
        Map<String, TerritoryManager> managers = new LinkedHashMap<>();
        Map<String, Integer> targetCounts = new LinkedHashMap<>();
        for (String tid : territoryIds) {
            managers.put(tid, new TerritoryManager(tid, territories.get(tid)));
            targetCounts.put(tid, 2 + rand.nextInt(2)); // 2 or 3
        }

        // Phase 1: Round-robin deal — guarantee every subfaction appears at least once
        List<SubfactionDef> shuffled = new ArrayList<>(allDefs);
        Collections.shuffle(shuffled, rand);
        int tidx = 0;
        for (SubfactionDef def : shuffled) {
            String tid = territoryIds.get(tidx % territoryIds.size());
            TerritoryManager mgr = managers.get(tid);
            mgr.addPresence(def.id, PresenceState.SCOUTING);
            log.info("  Territory '" + tid + "': assigned " + def.name +
                    " [" + def.id + "] → SCOUTING (guaranteed)");
            tidx++;
        }

        // Phase 2: Top-up — fill each territory to its target count
        for (String tid : territoryIds) {
            TerritoryManager mgr = managers.get(tid);
            int target = targetCounts.get(tid);
            List<SubfactionDef> candidates = new ArrayList<>();
            for (SubfactionDef def : allDefs) {
                if (mgr.getPresence(def.id) == null) {
                    candidates.add(def);
                }
            }
            Collections.shuffle(candidates, rand);
            int needed = target - mgr.getPresences().size();
            for (int i = 0; i < needed && i < candidates.size(); i++) {
                SubfactionDef def = candidates.get(i);
                mgr.addPresence(def.id, PresenceState.SCOUTING);
                log.info("  Territory '" + tid + "': assigned " + def.name +
                        " [" + def.id + "] → SCOUTING (top-up)");
            }
        }

        return new ArrayList<>(managers.values());
    }

    // ── Static lookup ────────────────────────────────────────────────────

    /**
     * Retrieve all TerritoryManagers from persistent data.
     * Returns an empty list if none are stored yet.
     */
    @SuppressWarnings("unchecked")
    public static List<TerritoryManager> getManagers() {
        Object stored = Global.getSector().getPersistentData().get(PERSISTENT_KEY);
        if (stored instanceof List) {
            return (List<TerritoryManager>) stored;
        }
        return Collections.emptyList();
    }
}

