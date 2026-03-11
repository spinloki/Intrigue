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

    private static final long serialVersionUID = 2L;

    /**
     * Days between successive patrol launches for the same subfaction.
     * Short enough to allow overlapping patrols when max concurrency > 1.
     */
    private static final float PATROL_INTERVAL_DAYS = 10f;

    /**
     * Duration of a cross-system patrol in days.
     * Accounts for: prep (3d) + outbound travel (~3-5d) +
     * in-system patrol (25d) + return travel (~3-5d) + end (8d) ≈ 42-46d.
     */
    private static final float PATROL_DURATION_DAYS = 45f;

    /** Base success chance for a patrol op. */
    private static final float PATROL_SUCCESS_CHANCE = 0.75f;

    private final String territoryId;
    private final List<String> systemIds;
    private final Map<String, SubfactionPresence> presences; // subfactionId → presence
    private final List<BaseSlot> baseSlots;

    /** Per-subfaction randomized establishment thresholds, assigned on creation. */
    private final Map<String, Float> establishmentThresholds;

    /** Currently active operations. */
    private final List<ActiveOp> activeOps;

    /** Per-subfaction cooldown: days until next patrol can launch. */
    private final Map<String, Float> patrolCooldowns;

    /** Seeded RNG for op resolution. Deterministic given the territory ID. */
    private final Random opRand;

    public TerritoryState(String territoryId, List<String> systemIds) {
        this.territoryId = territoryId;
        this.systemIds = new ArrayList<>(systemIds);
        this.presences = new LinkedHashMap<>();
        this.baseSlots = new ArrayList<>();
        this.establishmentThresholds = new LinkedHashMap<>();
        this.activeOps = new ArrayList<>();
        this.patrolCooldowns = new LinkedHashMap<>();
        this.opRand = new Random(territoryId.hashCode() * 31L);
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

    public enum TickResultType {
        /** Subfaction should establish a base at the given slot. */
        ESTABLISH_BASE,
        /** A new operation was launched. */
        OP_LAUNCHED,
        /** An operation completed (success or failure). */
        OP_RESOLVED
    }

    /**
     * Result of a daily tick evaluation.
     * The caller (TerritoryManager or test harness) inspects this to decide
     * what side effects to execute.
     */
    public static class TickResult {
        public final TickResultType type;
        public final String subfactionId;

        // For ESTABLISH_BASE
        public final boolean shouldEstablishBase;
        public final BaseSlot slotToEstablish;

        // For OP_LAUNCHED / OP_RESOLVED
        public final ActiveOp op;

        /** ESTABLISH_BASE result. */
        public TickResult(String subfactionId, BaseSlot slotToEstablish) {
            this.type = TickResultType.ESTABLISH_BASE;
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = true;
            this.slotToEstablish = slotToEstablish;
            this.op = null;
        }

        /** OP_LAUNCHED or OP_RESOLVED result. */
        public TickResult(TickResultType type, String subfactionId, ActiveOp op) {
            this.type = type;
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = false;
            this.slotToEstablish = null;
            this.op = op;
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

        // 1. Advance presence timers and check for establishment
        for (SubfactionPresence presence : presences.values()) {
            presence.advanceDays(1f);
            TickResult result = evaluateTransition(presence, subfactionDefs);
            if (result != null) {
                results.add(result);
            }
        }

        // 2. Resolve completed operations
        Iterator<ActiveOp> it = activeOps.iterator();
        while (it.hasNext()) {
            ActiveOp op = it.next();
            if (op.advanceDay()) {
                // Timer expired — resolve probabilistically (unless already overridden)
                if (op.isPending()) {
                    op.resolve(opRand);
                }
                results.add(new TickResult(TickResultType.OP_RESOLVED, op.getSubfactionId(), op));
                it.remove();
            }
        }

        // 3. Launch new patrol ops for eligible subfactions
        for (SubfactionPresence presence : presences.values()) {
            if (!presence.getState().canLaunchOps()) continue;

            String sid = presence.getSubfactionId();

            // Tick cooldown
            Float cooldown = patrolCooldowns.get(sid);
            if (cooldown != null && cooldown > 0f) {
                patrolCooldowns.put(sid, cooldown - 1f);
                continue;
            }

            // Cap concurrent patrols based on presence strength
            int maxPatrols = maxConcurrentPatrols(presence.getState());
            if (countActiveOps(sid, ActiveOp.OpType.PATROL) >= maxPatrols) continue;

            // Pick a target system (any system other than the base's system)
            String originSystem = findBaseSystem(sid);
            if (originSystem == null) continue;

            String targetSystem = pickPatrolTarget(sid, originSystem);
            if (targetSystem == null) continue;

            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.PATROL, sid,
                    originSystem, targetSystem,
                    PATROL_DURATION_DAYS, PATROL_SUCCESS_CHANCE);
            activeOps.add(op);
            patrolCooldowns.put(sid, PATROL_INTERVAL_DAYS);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, sid, op));
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

                return new TickResult(presence.getSubfactionId(), slot);
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

    // ── Operation helpers ─────────────────────────────────────────────

    /** Count how many active ops of a given type a subfaction has. */
    private int countActiveOps(String subfactionId, ActiveOp.OpType type) {
        int count = 0;
        for (ActiveOp op : activeOps) {
            if (op.getSubfactionId().equals(subfactionId) && op.getType() == type) {
                count++;
            }
        }
        return count;
    }

    /** Max concurrent patrols allowed based on presence state. */
    private static int maxConcurrentPatrols(PresenceState state) {
        switch (state) {
            case ESTABLISHED: return 2;
            case FORTIFIED:   return 3;
            case DOMINANT:    return 4;
            default:          return 0;
        }
    }

    /** Find which system a subfaction's base is in, or null. */
    private String findBaseSystem(String subfactionId) {
        for (BaseSlot slot : baseSlots) {
            if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                return slot.getSystemId();
            }
        }
        return null;
    }

    /** Pick a patrol target system (different from origin). */
    private String pickPatrolTarget(String subfactionId, String originSystem) {
        List<String> candidates = new ArrayList<>();
        for (String sid : systemIds) {
            if (!sid.equals(originSystem)) {
                candidates.add(sid);
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(opRand.nextInt(candidates.size()));
    }

    /**
     * Override an active operation's outcome. Used when the player physically
     * intervenes (e.g. destroys the patrol fleet). The execution layer calls this
     * so the decision layer records the real outcome instead of the probabilistic one.
     *
     * @return true if the op was found and overridden.
     */
    public boolean overrideOpOutcome(long opId, ActiveOp.OpOutcome forced) {
        for (ActiveOp op : activeOps) {
            if (op.getOpId() == opId) {
                op.overrideOutcome(forced);
                return true;
            }
        }
        return false;
    }

    /** Get all currently active operations (read-only snapshot). */
    public List<ActiveOp> getActiveOps() {
        return Collections.unmodifiableList(new ArrayList<>(activeOps));
    }

    // ── Base destruction ─────────────────────────────────────────────────

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
        sb.append(baseSlots.size()).append(" base slots, ");
        sb.append(activeOps.size()).append(" active ops\n");
        for (SubfactionPresence p : presences.values()) {
            sb.append("  ").append(p).append("\n");
        }
        for (BaseSlot slot : baseSlots) {
            sb.append("  ").append(slot).append("\n");
        }
        for (ActiveOp op : activeOps) {
            sb.append("  OP: ").append(op).append("\n");
        }
        return sb.toString();
    }
}
