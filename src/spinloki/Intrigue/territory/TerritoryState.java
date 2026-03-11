package spinloki.Intrigue.territory;

import spinloki.Intrigue.subfaction.SubfactionDef;

import java.io.Serializable;
import java.util.*;

/**
 * Pure decision-logic state for a single territory. Zero Starsector imports.
 *
 * <p>Holds presence tracking, base slot occupancy, and establishment thresholds.
 * All mutation methods are deterministic given their inputs — no calls to
 * {@code Global.getSector()} or any game API.</p>
 *
 * <p>{@link TerritoryManager} holds one of these as a field, delegates decision
 * questions to it, then executes Starsector-specific side effects (spawning
 * entities, checking destruction) separately. The test harness can instantiate
 * this class directly without any game dependencies.</p>
 */
public class TerritoryState implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String territoryId;
    private final List<String> systemIds;
    private final Map<String, SubfactionPresence> presences; // subfactionId → presence
    private final List<BaseSlot> baseSlots;

    /** Per-subfaction randomized establishment thresholds, assigned on creation. */
    private final Map<String, Float> establishmentThresholds;

    public TerritoryState(String territoryId, List<String> systemIds) {
        this.territoryId = territoryId;
        this.systemIds = new ArrayList<>(systemIds);
        this.presences = new LinkedHashMap<>();
        this.baseSlots = new ArrayList<>();
        this.establishmentThresholds = new LinkedHashMap<>();
    }

    // ── Presence management ──────────────────────────────────────────────

    /**
     * Add a subfaction presence to this territory.
     * Computes and stores the randomized establishment threshold.
     */
    public void addPresence(SubfactionDef def, PresenceState initialState) {
        presences.put(def.id, new SubfactionPresence(def.id, initialState));
        Random rand = new Random(def.id.hashCode() + territoryId.hashCode());
        float threshold = def.daysToEstablishBase + rand.nextFloat() * def.daysToEstablishJitter;
        establishmentThresholds.put(def.id, threshold);
    }

    public SubfactionPresence getPresence(String subfactionId) {
        return presences.get(subfactionId);
    }

    public Map<String, SubfactionPresence> getPresences() {
        return Collections.unmodifiableMap(presences);
    }

    // ── Base slot management ─────────────────────────────────────────────

    public void setBaseSlots(List<BaseSlot> slots) {
        this.baseSlots.clear();
        this.baseSlots.addAll(slots);
    }

    public List<BaseSlot> getBaseSlots() {
        return Collections.unmodifiableList(baseSlots);
    }

    /**
     * Pick an available base slot for a subfaction, respecting slot type preferences.
     * Preferred types are tried first; falls back to any unoccupied slot.
     *
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

    // ── Daily tick logic ─────────────────────────────────────────────────

    /**
     * Result of a daily tick evaluation for one subfaction.
     * The caller (TerritoryManager or test harness) inspects this to decide
     * what side effects to execute.
     */
    public static class TickResult {
        public final String subfactionId;
        public final boolean shouldEstablishBase;
        public final BaseSlot slotToEstablish;

        public TickResult(String subfactionId, boolean shouldEstablishBase, BaseSlot slotToEstablish) {
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = shouldEstablishBase;
            this.slotToEstablish = slotToEstablish;
        }
    }

    /**
     * Advance all presences by one day and evaluate state transitions.
     * Returns a list of actions that need to be executed (base spawning, etc.).
     *
     * @param subfactionDefs Lookup map: subfaction ID → SubfactionDef. Needed for
     *                       slot preference during establishment.
     * @return List of tick results describing required side effects.
     */
    public List<TickResult> advanceDay(Map<String, SubfactionDef> subfactionDefs) {
        List<TickResult> results = new ArrayList<>();

        for (SubfactionPresence presence : presences.values()) {
            presence.advanceDays(1f);
            TickResult result = evaluateTransition(presence, subfactionDefs);
            if (result != null) {
                results.add(result);
            }
        }

        return results;
    }

    /**
     * Evaluate whether a subfaction should transition state.
     * Returns a TickResult if action is needed, null otherwise.
     */
    private TickResult evaluateTransition(SubfactionPresence presence,
                                          Map<String, SubfactionDef> subfactionDefs) {
        if (presence.getState() == PresenceState.SCOUTING) {
            Float threshold = establishmentThresholds.get(presence.getSubfactionId());
            if (threshold == null) threshold = 25f;

            if (presence.getDaysSinceStateChange() >= threshold) {
                SubfactionDef def = subfactionDefs.get(presence.getSubfactionId());
                if (def == null) return null;

                BaseSlot slot = pickSlot(def);
                if (slot == null) return null;

                return new TickResult(presence.getSubfactionId(), true, slot);
            }
        }
        return null;
    }

    /**
     * Confirm that a base was successfully established. Transitions the subfaction
     * to ESTABLISHED and claims the slot. Called by the manager after the game-world
     * side effect (BaseSpawner) succeeds, or directly by the test harness.
     */
    public void confirmEstablishment(String subfactionId, BaseSlot slot) {
        SubfactionPresence presence = presences.get(subfactionId);
        if (presence != null) {
            presence.setState(PresenceState.ESTABLISHED);
        }
        slot.claim(subfactionId);
    }

    /**
     * Handle base destruction: release the slot and revert the subfaction to SCOUTING.
     * Called by TerritoryManager when it detects a destroyed entity, or by the test
     * harness to simulate base destruction.
     */
    public void handleBaseDestroyed(String subfactionId, BaseSlot slot) {
        slot.release();
        SubfactionPresence presence = presences.get(subfactionId);
        if (presence != null && (presence.getState() == PresenceState.ESTABLISHED
                || presence.getState() == PresenceState.FORTIFIED
                || presence.getState() == PresenceState.DOMINANT)) {
            presence.setState(PresenceState.SCOUTING);
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getTerritoryId() {
        return territoryId;
    }

    public List<String> getSystemIds() {
        return Collections.unmodifiableList(systemIds);
    }

    // ── Factory: subfaction distribution ──────────────────────────────────

    /**
     * Distribute subfactions across a set of territories.
     * Pure logic — no Starsector dependencies.
     *
     * <p>Guarantees every subfaction appears in at least one territory before allowing
     * duplicates. Each territory gets 2–3 subfactions.</p>
     *
     * @param territories Map of territory ID → system IDs.
     * @param allDefs     All subfaction definitions.
     * @param rand        Random source (caller-provided for reproducibility).
     * @return Map of territory ID → TerritoryState, fully populated with presences.
     */
    public static Map<String, TerritoryState> distributeSubfactions(
            Map<String, List<String>> territories,
            List<SubfactionDef> allDefs,
            Random rand) {

        List<String> territoryIds = new ArrayList<>(territories.keySet());

        // Create empty states and decide target counts (2–3 each)
        Map<String, TerritoryState> states = new LinkedHashMap<>();
        Map<String, Integer> targetCounts = new LinkedHashMap<>();
        for (String tid : territoryIds) {
            states.put(tid, new TerritoryState(tid, territories.get(tid)));
            targetCounts.put(tid, 2 + rand.nextInt(2));
        }

        // Phase 1: Round-robin deal — guarantee every subfaction appears at least once
        List<SubfactionDef> shuffled = new ArrayList<>(allDefs);
        Collections.shuffle(shuffled, rand);
        int tidx = 0;
        for (SubfactionDef def : shuffled) {
            String tid = territoryIds.get(tidx % territoryIds.size());
            states.get(tid).addPresence(def, PresenceState.SCOUTING);
            tidx++;
        }

        // Phase 2: Top-up — fill each territory to its target count
        for (String tid : territoryIds) {
            TerritoryState state = states.get(tid);
            int target = targetCounts.get(tid);
            List<SubfactionDef> candidates = new ArrayList<>();
            for (SubfactionDef def : allDefs) {
                if (state.getPresence(def.id) == null) {
                    candidates.add(def);
                }
            }
            Collections.shuffle(candidates, rand);
            int needed = target - state.getPresences().size();
            for (int i = 0; i < needed && i < candidates.size(); i++) {
                state.addPresence(candidates.get(i), PresenceState.SCOUTING);
            }
        }

        return states;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Territory '").append(territoryId).append("': ");
        sb.append(systemIds.size()).append(" systems, ");
        sb.append(presences.size()).append(" subfactions, ");
        sb.append(baseSlots.size()).append(" base slots\n");
        for (SubfactionPresence p : presences.values()) {
            sb.append("  ").append(p).append("\n");
        }
        for (BaseSlot slot : baseSlots) {
            sb.append("  ").append(slot).append("\n");
        }
        return sb.toString();
    }
}
