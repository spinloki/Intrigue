package spinloki.Intrigue.territory;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;

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
 * present and in what state ({@link SubfactionPresence}), manages base slots, and
 * drives state transitions.</p>
 */
public class TerritoryManager implements EveryFrameScript, Serializable {

    private static final long serialVersionUID = 2L;
    private static final Logger log = Global.getLogger(TerritoryManager.class);

    /** Persistent data key for the list of all TerritoryManagers. */
    public static final String PERSISTENT_KEY = "intrigue_territory_managers";

    private final String territoryId;
    private final List<String> systemIds;
    private final Map<String, SubfactionPresence> presences; // subfactionId → presence
    private final List<BaseSlot> baseSlots;

    /** Per-subfaction randomized establishment thresholds, assigned on creation. */
    private final Map<String, Float> establishmentThresholds;

    /** Accumulated fractional days since last day-tick processing. */
    private float dayAccumulator = 0f;

    /** Tracks whether we've logged the initial diagnostic message. */
    private transient boolean loggedInit = false;

    public TerritoryManager(String territoryId, List<String> systemIds) {
        this.territoryId = territoryId;
        this.systemIds = new ArrayList<>(systemIds);
        this.presences = new LinkedHashMap<>();
        this.baseSlots = new ArrayList<>();
        this.establishmentThresholds = new LinkedHashMap<>();
    }

    // ── Presence management ──────────────────────────────────────────────

    /**
     * Add a subfaction presence to this territory.
     */
    public void addPresence(SubfactionDef def, PresenceState initialState) {
        presences.put(def.id, new SubfactionPresence(def.id, initialState));
        // Assign a randomized establishment threshold from subfaction config
        Random rand = new Random(def.id.hashCode() + territoryId.hashCode());
        float threshold = def.daysToEstablishBase + rand.nextFloat() * def.daysToEstablishJitter;
        establishmentThresholds.put(def.id, threshold);
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

    // ── Base slot management ─────────────────────────────────────────────

    /**
     * Set the pre-computed base slots for this territory.
     * Called once after territory generation.
     */
    public void setBaseSlots(List<BaseSlot> slots) {
        this.baseSlots.clear();
        this.baseSlots.addAll(slots);
    }

    /**
     * Get all base slots (unmodifiable view).
     */
    public List<BaseSlot> getBaseSlots() {
        return Collections.unmodifiableList(baseSlots);
    }

    /**
     * Pick an available base slot for a subfaction, respecting slot type preferences.
     * Preferred types are tried first; falls back to any unoccupied slot.
     *
     * @param def The subfaction to pick a slot for.
     * @return The picked slot, or null if none available.
     */
    public BaseSlot pickSlot(SubfactionDef def) {
        List<BaseSlot> available = new ArrayList<>();
        for (BaseSlot slot : baseSlots) {
            if (!slot.isOccupied()) {
                available.add(slot);
            }
        }

        if (available.isEmpty()) return null;

        // Try preferred slot types first
        if (!def.preferredSlotTypes.isEmpty()) {
            for (BaseSlotType preferred : def.preferredSlotTypes) {
                List<BaseSlot> matching = new ArrayList<>();
                for (BaseSlot slot : available) {
                    if (slot.getSlotType() == preferred) {
                        matching.add(slot);
                    }
                }
                if (!matching.isEmpty()) {
                    Random rand = new Random(def.id.hashCode() + territoryId.hashCode() + available.size());
                    return matching.get(rand.nextInt(matching.size()));
                }
            }
        }

        // No preferred slot available — pick any
        Random rand = new Random(def.id.hashCode() + territoryId.hashCode() + available.size());
        return available.get(rand.nextInt(available.size()));
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
                    " with " + presences.size() + " subfactions, " +
                    baseSlots.size() + " base slots, " +
                    systemIds.size() + " systems");
            loggedInit = true;
        }

        // Convert frame time to days and accumulate
        float days = Misc.getDays(amount);
        dayAccumulator += days;

        // Process once per day
        if (dayAccumulator < 1f) return;
        dayAccumulator -= 1f;

        // Check for destroyed bases before advancing state
        checkForDestroyedBases();

        // Advance day counters on all presences and check for transitions
        for (SubfactionPresence presence : presences.values()) {
            presence.advanceDays(1f);
            checkStateTransitions(presence);
        }
    }

    /**
     * Check and execute state transitions for a subfaction presence.
     */
    private void checkStateTransitions(SubfactionPresence presence) {
        if (presence.getState() == PresenceState.SCOUTING) {
            Float threshold = establishmentThresholds.get(presence.getSubfactionId());
            if (threshold == null) threshold = 25f;

            if (presence.getDaysSinceStateChange() >= threshold) {
                tryEstablishBase(presence);
            }
        }
    }

    /**
     * Check all occupied base slots for destruction. If a base's station entity or market
     * no longer exists, release the slot and revert the subfaction to SCOUTING.
     */
    private void checkForDestroyedBases() {
        for (BaseSlot slot : baseSlots) {
            if (!slot.isOccupied() || slot.getStationEntityId() == null) continue;

            boolean destroyed = false;
            SectorEntityToken station = findStationEntity(slot);
            if (station == null) {
                destroyed = true;
            } else {
                MarketAPI market = station.getMarket();
                if (market == null || Global.getSector().getEconomy().getMarket(market.getId()) == null) {
                    destroyed = true;
                }
            }

            if (!destroyed) continue;

            String subfactionId = slot.getOccupiedBySubfactionId();
            log.info("TerritoryManager [" + territoryId + "]: base destroyed for " +
                    subfactionId + " at " + slot.getLabel() + " — releasing slot, reverting to SCOUTING");
            slot.release();

            SubfactionPresence presence = presences.get(subfactionId);
            if (presence != null && presence.getState() == PresenceState.ESTABLISHED) {
                presence.setState(PresenceState.SCOUTING);
            }
        }
    }

    /**
     * Find the station entity for a base slot by searching its star system.
     */
    private SectorEntityToken findStationEntity(BaseSlot slot) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(slot.getSystemId())) {
                return system.getEntityById(slot.getStationEntityId());
            }
        }
        return null;
    }

    /**
     * Attempt to transition a subfaction from SCOUTING to ESTABLISHED by spawning a base.
     */
    @SuppressWarnings("unchecked")
    private void tryEstablishBase(SubfactionPresence presence) {
        // Look up the SubfactionDef
        List<SubfactionDef> defs = (List<SubfactionDef>)
                Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
        if (defs == null) {
            log.error("TerritoryManager: no subfaction defs in persistent data — cannot establish base");
            return;
        }

        SubfactionDef def = null;
        for (SubfactionDef d : defs) {
            if (d.id.equals(presence.getSubfactionId())) {
                def = d;
                break;
            }
        }
        if (def == null) {
            log.error("TerritoryManager: subfaction def not found for '" +
                    presence.getSubfactionId() + "' — cannot establish base");
            return;
        }

        // Pick a slot
        BaseSlot slot = pickSlot(def);
        if (slot == null) {
            log.warn("TerritoryManager [" + territoryId + "]: no available base slot for " +
                    def.name + " — remaining in SCOUTING");
            return;
        }

        // Spawn the base
        SectorEntityToken station = BaseSpawner.spawnBase(slot, def);
        if (station == null) {
            log.error("TerritoryManager [" + territoryId + "]: base spawning failed for " +
                    def.name + " at " + slot.getLabel());
            return;
        }

        // Transition state
        presence.setState(PresenceState.ESTABLISHED);
        log.info("TerritoryManager [" + territoryId + "]: " + def.name +
                " SCOUTING → ESTABLISHED at " + slot.getLabel() +
                " after " + String.format("%.1f", presence.getDaysSinceStateChange()) + " days");
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
            mgr.addPresence(def, PresenceState.SCOUTING);
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
                mgr.addPresence(def, PresenceState.SCOUTING);
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

