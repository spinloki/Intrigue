package spinloki.Intrigue.territory;

import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.io.Serializable;
import java.util.*;

/**
 * Pure decision-logic state for a single territory. Zero Starsector imports.
 *
 * <p>Holds presence tracking, base slot occupancy, establishment thresholds,
 * factor ledgers, and the periodic evaluation cycle. All mutation methods are
 * deterministic given their inputs — no calls to {@code Global.getSector()}
 * or any game API.</p>
 *
 * <p>{@link TerritoryManager} holds one of these as a field, delegates decision
 * questions to it, then executes Starsector-specific side effects (spawning
 * entities, checking destruction) separately. The test harness can instantiate
 * this class directly without any game dependencies.</p>
 */
public class TerritoryState implements Serializable {

    private static final long serialVersionUID = 6L;

    // ── Vanilla-derived parent-faction relationship data ─────────────────

    /**
     * Baseline relationship between two parent factions.
     * Derived from vanilla Starsector's {@code SectorGen.initFactionRelationships()}.
     */
    public enum ParentRelation {
        /** Always hostile (hardcoded in SectorGen, e.g. pirates vs hegemony). */
        HOSTILE,
        /** Not hostile by default (neutral or friendly in vanilla). */
        NEUTRAL,
        /** Hostilities break out semi-randomly, emulating vanilla's FactionHostilityIntel. */
        VOLATILE
    }

    /**
     * Parent-faction pairs that are always hostile at baseline.
     * Source: {@code SectorGen.initFactionRelationships()} — all pairs set to
     * {@code RepLevel.HOSTILE} excluding the two commented-out "replaced by hostilities"
     * pairs (hegemony↔tritachyon, hegemony↔persean).
     */
    private static final Set<SubfactionPair> PARENT_HOSTILE_PAIRS;

    /**
     * Parent-faction pairs whose hostility comes and goes randomly, emulating
     * vanilla's {@code FactionHostilityIntel} / {@code CoreLifecyclePluginImpl}.
     * In vanilla, hegemony↔tritachyon and hegemony↔persean hostilities are NOT
     * static defaults — they're managed dynamically.
     */
    private static final Set<SubfactionPair> PARENT_VOLATILE_PAIRS;

    static {
        Set<SubfactionPair> hostile = new HashSet<>();
        // Pirates hostile to nearly everyone
        hostile.add(new SubfactionPair("hegemony", "pirates"));
        hostile.add(new SubfactionPair("tritachyon", "pirates"));
        hostile.add(new SubfactionPair("luddic_church", "pirates"));
        hostile.add(new SubfactionPair("sindrian_diktat", "pirates"));
        hostile.add(new SubfactionPair("persean", "pirates"));
        hostile.add(new SubfactionPair("independent", "pirates"));
        // Luddic Path hostile to most major factions
        hostile.add(new SubfactionPair("hegemony", "luddic_path"));
        hostile.add(new SubfactionPair("tritachyon", "luddic_path"));
        hostile.add(new SubfactionPair("sindrian_diktat", "luddic_path"));
        hostile.add(new SubfactionPair("persean", "luddic_path"));
        hostile.add(new SubfactionPair("independent", "luddic_path"));
        PARENT_HOSTILE_PAIRS = Collections.unmodifiableSet(hostile);

        Set<SubfactionPair> volatile_ = new HashSet<>();
        // These two pairs are commented out in SectorGen with:
        // "replaced by hostilities set in CoreLifecyclePluginImpl"
        volatile_.add(new SubfactionPair("hegemony", "tritachyon"));
        volatile_.add(new SubfactionPair("hegemony", "persean"));
        PARENT_VOLATILE_PAIRS = Collections.unmodifiableSet(volatile_);
    }

    /**
     * Look up the vanilla baseline relationship between two parent factions.
     *
     * @return HOSTILE, NEUTRAL, or VOLATILE.
     */
    public static ParentRelation getParentRelation(String parentA, String parentB) {
        if (parentA.equals(parentB)) return ParentRelation.NEUTRAL;
        SubfactionPair pair = new SubfactionPair(parentA, parentB);
        if (PARENT_HOSTILE_PAIRS.contains(pair)) return ParentRelation.HOSTILE;
        if (PARENT_VOLATILE_PAIRS.contains(pair)) return ParentRelation.VOLATILE;
        return ParentRelation.NEUTRAL;
    }

    // ── Constants ────────────────────────────────────────────────────────

    /**
     * Days between successive patrol launches for the same subfaction.
     * Short enough to allow overlapping patrols when max concurrency > 1.
     */
    private static final float PATROL_INTERVAL_DAYS = 10f;

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

    /** Days until next evaluation cycle fires. */
    private float daysUntilEvaluation;

    /** Active entanglements, keyed by unordered subfaction pair. At most one per pair. */
    private final Map<SubfactionPair, ActiveEntanglement> entanglements;

    /** Cooldowns for entanglement-spawned ops, keyed by SubfactionPair. */
    private final Map<SubfactionPair, Float> entanglementOpCooldowns;

    /** Days since the last Council op completed (or since territory creation). */
    private float daysSinceLastCouncil;

    /**
     * Active dynamic hostilities for VOLATILE parent-faction pairs in this territory.
     * Key = parent-faction pair (e.g. hegemony↔tritachyon); Value = remaining days.
     * Emulates vanilla's FactionHostilityIntel — hostilities break out semi-randomly
     * and last for a bounded duration.
     */
    private final Map<SubfactionPair, Float> dynamicHostilities;

    /** Per-day probability that a VOLATILE pair (currently at peace) starts hostilities. */
    private static final float VOLATILE_HOSTILITY_CHANCE_PER_DAY = 0.005f; // ~once every 200 days

    /** Minimum duration of a dynamic hostility event (days). */
    private static final float VOLATILE_HOSTILITY_MIN_DAYS = 90f;

    /** Maximum duration of a dynamic hostility event (days). */
    private static final float VOLATILE_HOSTILITY_MAX_DAYS = 180f;

    /** Minimum days between council ops. */
    private static final float COUNCIL_COOLDOWN_DAYS = 120f;

    /**
     * Days a subfaction can sit at ESTABLISHED before an external invasion is triggered.
     * After surviving (or if no invader is available), the timer resets for another period.
     */
    private static final float STAGNATION_INVASION_DAYS = 180f;

    /** Minimum number of hostile entanglements to trigger a council. */


    public TerritoryState(String territoryId, List<String> systemIds) {
        this.territoryId = territoryId;
        this.systemIds = new ArrayList<>(systemIds);
        this.presences = new LinkedHashMap<>();
        this.baseSlots = new ArrayList<>();
        this.establishmentThresholds = new LinkedHashMap<>();
        this.activeOps = new ArrayList<>();
        this.patrolCooldowns = new LinkedHashMap<>();
        this.opRand = new Random(territoryId.hashCode() * 31L);
        this.daysUntilEvaluation = -1f; // set on first advanceDay with settings
        this.entanglements = new LinkedHashMap<>();
        this.entanglementOpCooldowns = new LinkedHashMap<>();
        this.daysSinceLastCouncil = COUNCIL_COOLDOWN_DAYS; // eligible immediately
        this.dynamicHostilities = new LinkedHashMap<>();
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

    // ── Entanglement management ──────────────────────────────────────────

    /**
     * Determine whether two subfactions are hostile in this territory.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Entanglement that {@code setsHostile} → hostile.</li>
     *   <li>Entanglement that {@code suppressesHostile} → not hostile.</li>
     *   <li>Same parent faction → not hostile.</li>
     *   <li>Vanilla baseline: HOSTILE pairs → hostile, VOLATILE pairs → check
     *       dynamic hostility state, NEUTRAL pairs → not hostile.</li>
     * </ol>
     *
     * @return true if the pair should be hostile (fleets shoot each other).
     */
    public boolean isHostile(String a, String b, Map<String, SubfactionDef> subfactionDefs) {
        SubfactionPair pair = new SubfactionPair(a, b);
        ActiveEntanglement ent = entanglements.get(pair);
        if (ent != null) {
            if (ent.setsHostile()) return true;
            if (ent.suppressesHostile()) return false;
            // Entanglement has no hostility effect (e.g. PROXY_SUPPORT) — fall through to baseline.
        }
        // Look up parent factions
        SubfactionDef defA = subfactionDefs.get(a);
        SubfactionDef defB = subfactionDefs.get(b);
        if (defA == null || defB == null) return true; // unknown subfaction → hostile by default

        // Vanilla-derived baseline (replaces old "different parent = hostile" rule)
        ParentRelation rel = getParentRelation(defA.parentFactionId, defB.parentFactionId);
        switch (rel) {
            case HOSTILE:  return true;
            case VOLATILE:
                // Check whether this volatile pair currently has active hostilities
                SubfactionPair parentPair = new SubfactionPair(defA.parentFactionId, defB.parentFactionId);
                return dynamicHostilities.containsKey(parentPair);
            case NEUTRAL:
            default:       return false;
        }
    }

    /** Get the active entanglement between a pair, or null. */
    public ActiveEntanglement getEntanglement(String a, String b) {
        return entanglements.get(new SubfactionPair(a, b));
    }

    /** Get the active entanglement for a pre-built pair key, or null. */
    public ActiveEntanglement getEntanglement(SubfactionPair pair) {
        return entanglements.get(pair);
    }

    /**
     * Set (or replace) the entanglement on a subfaction pair.
     * Returns the previous entanglement on this pair, or null.
     */
    public ActiveEntanglement setEntanglement(ActiveEntanglement entanglement) {
        return entanglements.put(entanglement.getPair(), entanglement);
    }

    /** Remove the entanglement on a pair. Returns the removed entanglement, or null. */
    public ActiveEntanglement removeEntanglement(String a, String b) {
        return entanglements.remove(new SubfactionPair(a, b));
    }

    /** Remove the entanglement for a pre-built pair key. */
    public ActiveEntanglement removeEntanglement(SubfactionPair pair) {
        return entanglements.remove(pair);
    }

    /** Read-only view of all active entanglements in this territory. */
    public Map<SubfactionPair, ActiveEntanglement> getEntanglements() {
        return Collections.unmodifiableMap(entanglements);
    }

    /** Read-only view of active dynamic hostilities (parent-faction pairs → remaining days). */
    public Map<SubfactionPair, Float> getDynamicHostilities() {
        return Collections.unmodifiableMap(dynamicHostilities);
    }

    /**
     * Tick dynamic hostilities for VOLATILE parent-faction pairs.
     * <ul>
     *   <li>Active hostilities: decrement timer; remove and emit HOSTILITIES_ENDED when expired.</li>
     *   <li>Inactive volatile pairs (where both parents have subfactions present): roll random
     *       chance to START hostilities, emulating vanilla's FactionHostilityIntel.</li>
     * </ul>
     *
     * @return TickResults for any hostility state changes.
     */
    private List<TickResult> tickDynamicHostilities(Map<String, SubfactionDef> subfactionDefs) {
        List<TickResult> results = new ArrayList<>();

        // Tick down active dynamic hostilities
        Iterator<Map.Entry<SubfactionPair, Float>> it = dynamicHostilities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SubfactionPair, Float> entry = it.next();
            float remaining = entry.getValue() - 1f;
            if (remaining <= 0f) {
                it.remove();
                results.add(new TickResult(TickResultType.HOSTILITIES_ENDED, entry.getKey()));
            } else {
                entry.setValue(remaining);
            }
        }

        // Collect parent faction IDs actually present in this territory
        Set<String> presentParents = new HashSet<>();
        for (SubfactionPresence presence : presences.values()) {
            SubfactionDef def = subfactionDefs.get(presence.getSubfactionId());
            if (def != null) {
                presentParents.add(def.parentFactionId);
            }
        }

        // For each volatile pair where both parents are present and not already hostile,
        // roll a random chance to start hostilities
        for (SubfactionPair volatilePair : PARENT_VOLATILE_PAIRS) {
            if (dynamicHostilities.containsKey(volatilePair)) continue;
            if (!presentParents.contains(volatilePair.getFirst())) continue;
            if (!presentParents.contains(volatilePair.getSecond())) continue;

            if (opRand.nextFloat() < VOLATILE_HOSTILITY_CHANCE_PER_DAY) {
                float duration = VOLATILE_HOSTILITY_MIN_DAYS
                        + opRand.nextFloat() * (VOLATILE_HOSTILITY_MAX_DAYS - VOLATILE_HOSTILITY_MIN_DAYS);
                dynamicHostilities.put(volatilePair, duration);
                results.add(new TickResult(TickResultType.HOSTILITIES_STARTED, volatilePair));
            }
        }

        return results;
    }

    /**
     * Create (or replace) an entanglement and return the appropriate TickResult.
     * Convenience for code that needs to emit the result into a results list.
     */
    public TickResult applyEntanglement(ActiveEntanglement entanglement) {
        ActiveEntanglement previous = setEntanglement(entanglement);
        if (previous != null) {
            return new TickResult(entanglement, previous);
        }
        return new TickResult(TickResultType.ENTANGLEMENT_CREATED, entanglement);
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
        OP_RESOLVED,
        /** A subfaction was promoted to a higher presence state. */
        PRESENCE_PROMOTED,
        /** A subfaction was demoted to a lower presence state. */
        PRESENCE_DEMOTED,
        /** A new entanglement was created (no previous entanglement on this pair). */
        ENTANGLEMENT_CREATED,
        /** An entanglement expired (timer reached zero or condition cleared). */
        ENTANGLEMENT_EXPIRED,
        /** An existing entanglement was replaced by a new one on the same pair. */
        ENTANGLEMENT_REPLACED,
        /** A VOLATILE parent-pair's dynamic hostilities have broken out. */
        HOSTILITIES_STARTED,
        /** A VOLATILE parent-pair's dynamic hostilities have ended. */
        HOSTILITIES_ENDED,
        /** A subfaction was completely evicted from the territory (ESTABLISHED → NONE). */
        SUBFACTION_EVICTED
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

        // For PRESENCE_PROMOTED / PRESENCE_DEMOTED
        public final PresenceState oldState;
        public final PresenceState newState;

        // For ENTANGLEMENT_CREATED / ENTANGLEMENT_EXPIRED / ENTANGLEMENT_REPLACED
        public final ActiveEntanglement entanglement;
        /** The previous entanglement on this pair (non-null only for ENTANGLEMENT_REPLACED). */
        public final ActiveEntanglement replacedEntanglement;

        // For HOSTILITIES_STARTED / HOSTILITIES_ENDED
        /** The parent-faction pair whose dynamic hostility changed (non-null only for HOSTILITIES_*). */
        public final SubfactionPair parentPair;

        /** ESTABLISH_BASE result. */
        public TickResult(String subfactionId, BaseSlot slotToEstablish) {
            this.type = TickResultType.ESTABLISH_BASE;
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = true;
            this.slotToEstablish = slotToEstablish;
            this.op = null;
            this.oldState = null;
            this.newState = null;
            this.entanglement = null;
            this.replacedEntanglement = null;
            this.parentPair = null;
        }

        /** OP_LAUNCHED or OP_RESOLVED result. */
        public TickResult(TickResultType type, String subfactionId, ActiveOp op) {
            this.type = type;
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = false;
            this.slotToEstablish = null;
            this.op = op;
            this.oldState = null;
            this.newState = null;
            this.entanglement = null;
            this.replacedEntanglement = null;
            this.parentPair = null;
        }

        /** PRESENCE_PROMOTED or PRESENCE_DEMOTED result. */
        public TickResult(TickResultType type, String subfactionId,
                          PresenceState oldState, PresenceState newState) {
            this.type = type;
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = false;
            this.slotToEstablish = null;
            this.op = null;
            this.oldState = oldState;
            this.newState = newState;
            this.entanglement = null;
            this.replacedEntanglement = null;
            this.parentPair = null;
        }

        /** ENTANGLEMENT_CREATED or ENTANGLEMENT_EXPIRED result. */
        public TickResult(TickResultType type, ActiveEntanglement entanglement) {
            this.type = type;
            this.subfactionId = null;
            this.shouldEstablishBase = false;
            this.slotToEstablish = null;
            this.op = null;
            this.oldState = null;
            this.newState = null;
            this.entanglement = entanglement;
            this.replacedEntanglement = null;
            this.parentPair = null;
        }

        /** ENTANGLEMENT_REPLACED result. */
        public TickResult(ActiveEntanglement newEntanglement, ActiveEntanglement replaced) {
            this.type = TickResultType.ENTANGLEMENT_REPLACED;
            this.subfactionId = null;
            this.shouldEstablishBase = false;
            this.slotToEstablish = null;
            this.op = null;
            this.oldState = null;
            this.newState = null;
            this.entanglement = newEntanglement;
            this.replacedEntanglement = replaced;
            this.parentPair = null;
        }

        /** HOSTILITIES_STARTED or HOSTILITIES_ENDED result. */
        public TickResult(TickResultType type, SubfactionPair parentPair) {
            this.type = type;
            this.subfactionId = null;
            this.shouldEstablishBase = false;
            this.slotToEstablish = null;
            this.op = null;
            this.oldState = null;
            this.newState = null;
            this.entanglement = null;
            this.replacedEntanglement = null;
            this.parentPair = parentPair;
        }
    }

    /**
     * Advance all presences by one day and evaluate state transitions.
     * Returns a list of actions that need to be executed (base spawning, etc.).
     *
     * @param subfactionDefs Lookup map: subfaction ID → SubfactionDef. Needed for
     *                       slot preference during establishment.
     * @param settings       Configuration for factor weights, eval interval, thresholds.
     * @return List of tick results describing required side effects.
     */
    public List<TickResult> advanceDay(Map<String, SubfactionDef> subfactionDefs,
                                       IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        // Initialize evaluation timer on first tick
        if (daysUntilEvaluation < 0f) {
            daysUntilEvaluation = settings.evaluationIntervalDays;
        }

        // 1. Advance presence timers, tick factors, resolve expired desertions
        for (SubfactionPresence presence : presences.values()) {
            presence.advanceDays(1f);
            List<PresenceFactor> expired = presence.tickFactors();

            // Resolve expired DESERTION factors into QUELLED or SPIRALED
            for (PresenceFactor ex : expired) {
                if (ex.getType() == PresenceFactor.FactorType.DESERTION) {
                    resolveDesertion(presence, settings);
                }
            }

            TickResult result = evaluateTransition(presence, subfactionDefs);
            if (result != null) {
                results.add(result);
            }
        }

        // 2. Recalculate intrinsic and conditional factors (pure-Java evaluable)
        recalculateFactors(settings, subfactionDefs);

        // 2b. Tick entanglement timers and remove expired ones
        Iterator<Map.Entry<SubfactionPair, ActiveEntanglement>> entIt =
                entanglements.entrySet().iterator();
        while (entIt.hasNext()) {
            ActiveEntanglement ent = entIt.next().getValue();
            if (ent.advanceDay()) {
                entIt.remove();
                results.add(new TickResult(TickResultType.ENTANGLEMENT_EXPIRED, ent));
            }
        }

        // 2c. Tick dynamic hostilities for VOLATILE parent-faction pairs
        results.addAll(tickDynamicHostilities(subfactionDefs));

        // 3. Resolve completed operations and generate factors from results
        Iterator<ActiveOp> it = activeOps.iterator();
        while (it.hasNext()) {
            ActiveOp op = it.next();
            if (op.advanceDay()) {
                // Timer expired — resolve probabilistically (unless already overridden)
                if (op.isPending()) {
                    applyOpInteractions(op);
                    op.resolve(opRand);
                }
                results.add(new TickResult(TickResultType.OP_RESOLVED, op.getSubfactionId(), op));
                generateOpResultFactors(op, settings);
                results.addAll(evaluateOpEntanglementTriggers(op, subfactionDefs, settings));
                it.remove();
            }
        }

        // 4. Launch new patrol ops for eligible subfactions
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

            float duration = settings.getOpDuration("PATROL", 45f);
            float chance = settings.getOpSuccessChance("PATROL", 0.75f);
            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.PATROL, sid,
                    originSystem, targetSystem,
                    duration, chance);
            activeOps.add(op);
            patrolCooldowns.put(sid, PATROL_INTERVAL_DAYS);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, sid, op));
        }

        // 4b. Launch entanglement-spawned ops (Protection Patrol, Joint Strike)
        results.addAll(launchEntanglementOps(subfactionDefs, settings));

        // 5. Roll for new desertion events
        for (SubfactionPresence presence : presences.values()) {
            if (!presence.getState().canLaunchOps()) continue;
            float chance = settings.getDesertionChance(presence.getState());
            if (chance > 0f && opRand.nextFloat() < chance) {
                float duration = settings.desertionDurationDays;
                presence.addFactor(new PresenceFactor(
                        PresenceFactor.FactorType.DESERTION,
                        PresenceFactor.Polarity.PRESSURE, 0,
                        duration, null));
            }
        }

        // 6. Evaluation cycle — check for promotion/demotion ops
        daysUntilEvaluation -= 1f;
        if (daysUntilEvaluation <= 0f) {
            daysUntilEvaluation = settings.evaluationIntervalDays;
            results.addAll(evaluateCycle(subfactionDefs, settings));
        }

        // 6b. Stagnation invasion — check ESTABLISHED subfactions that have stalled
        results.addAll(evaluateStagnationInvasions(subfactionDefs, settings));

        // 6c. Council trigger check (independent of evaluation cycle)
        daysSinceLastCouncil += 1f;
        if (daysSinceLastCouncil >= COUNCIL_COOLDOWN_DAYS) {
            List<TickResult> councilResults = evaluateCouncilTrigger(subfactionDefs, settings);
            results.addAll(councilResults);
        }

        // 7. Evaluate presence-based entanglement triggers (Territorial War, Civil War)
        results.addAll(evaluatePresenceEntanglementTriggers(subfactionDefs, settings));

        return results;
    }

    /**
     * Backward-compatible overload for callers that don't have settings yet.
     * Uses the old hardcoded defaults. This should be phased out.
     */
    public List<TickResult> advanceDay(Map<String, SubfactionDef> subfactionDefs) {
        return advanceDay(subfactionDefs, DEFAULT_SETTINGS);
    }

    /** Minimal default settings for backward compat. */
    private static final IntrigueSettings DEFAULT_SETTINGS;
    static {
        try {
            DEFAULT_SETTINGS = IntrigueSettings.parse(
                    "{\"evaluationIntervalDays\":60,\"transitionThreshold\":3}");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse default settings", e);
        }
    }

    // ── Factor management ────────────────────────────────────────────────

    /**
     * Resolve an expired DESERTION factor. The subfaction's current net balance
     * determines whether the desertion is quelled (leverage boost) or spirals
     * (pressure penalty). More net pressure → higher chance of spiraling.
     */
    private void resolveDesertion(SubfactionPresence presence, IntrigueSettings settings) {
        int net = presence.getNetBalance();

        // Base 50% chance to quell. Each point of net pressure shifts 10% toward spiraling,
        // each point of net leverage shifts 10% toward quelling.
        float quellChance = 0.5f + net * 0.1f;
        quellChance = Math.max(0.1f, Math.min(0.9f, quellChance)); // clamp [10%, 90%]

        if (opRand.nextFloat() < quellChance) {
            // Quelled — generates leverage
            IntrigueSettings.FactorDef def =
                    settings.getFactorDef(PresenceFactor.FactorType.DESERTION_QUELLED);
            if (def != null) {
                presence.addFactor(new PresenceFactor(
                        PresenceFactor.FactorType.DESERTION_QUELLED,
                        def.polarity, def.getWeight(presence.getState()),
                        def.durationDays, null));
            }
        } else {
            // Spiraled — generates pressure
            IntrigueSettings.FactorDef def =
                    settings.getFactorDef(PresenceFactor.FactorType.DESERTION_SPIRALED);
            if (def != null) {
                presence.addFactor(new PresenceFactor(
                        PresenceFactor.FactorType.DESERTION_SPIRALED,
                        def.polarity, def.getWeight(presence.getState()),
                        def.durationDays, null));
            }
        }
    }

    /**
     * Recalculate intrinsic and conditional factors that can be evaluated in pure Java.
     * Called every tick. Removes stale factors of these types, then re-adds current ones.
     */
    private void recalculateFactors(IntrigueSettings settings,
                                    Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence presence : presences.values()) {
            PresenceState state = presence.getState();
            if (!state.canLaunchOps()) continue; // No factors for SCOUTING/NONE

            // ── Intrinsic: Logistical Strain ──
            presence.removeFactorsByType(PresenceFactor.FactorType.LOGISTICAL_STRAIN);
            IntrigueSettings.FactorDef strainDef =
                    settings.getFactorDef(PresenceFactor.FactorType.LOGISTICAL_STRAIN);
            if (strainDef != null) {
                int w = strainDef.getWeight(state);
                if (w > 0) {
                    presence.addFactor(new PresenceFactor(
                            PresenceFactor.FactorType.LOGISTICAL_STRAIN,
                            strainDef.polarity, w, null));
                }
            }

            // ── Conditional: Hostile Presence ──
            presence.removeFactorsByType(PresenceFactor.FactorType.HOSTILE_PRESENCE);
            IntrigueSettings.FactorDef hostileDef =
                    settings.getFactorDef(PresenceFactor.FactorType.HOSTILE_PRESENCE);
            if (hostileDef != null) {
                int perHostile = hostileDef.getWeight(state);
                if (perHostile > 0) {
                    int hostileCount = countOtherSubfactions(presence.getSubfactionId(), true, subfactionDefs);
                    for (int i = 0; i < hostileCount; i++) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.HOSTILE_PRESENCE,
                                hostileDef.polarity, perHostile, null));
                    }
                }
            }

            // ── Conditional: Secure Comms (baseline leverage from having a base) ──
            presence.removeFactorsByType(PresenceFactor.FactorType.SECURE_COMMS);
            IntrigueSettings.FactorDef commsDef =
                    settings.getFactorDef(PresenceFactor.FactorType.SECURE_COMMS);
            if (commsDef != null) {
                int w = commsDef.getWeight(state);
                if (w > 0) {
                    presence.addFactor(new PresenceFactor(
                            PresenceFactor.FactorType.SECURE_COMMS,
                            commsDef.polarity, w, null));
                }
            }

            // ── Conditional: Neutral Presence (only affects DOMINANT) ──
            presence.removeFactorsByType(PresenceFactor.FactorType.NEUTRAL_PRESENCE);
            IntrigueSettings.FactorDef neutralDef =
                    settings.getFactorDef(PresenceFactor.FactorType.NEUTRAL_PRESENCE);
            if (neutralDef != null) {
                int perNeutral = neutralDef.getWeight(state);
                if (perNeutral > 0) {
                    int neutralCount = countOtherSubfactions(presence.getSubfactionId(), false, subfactionDefs);
                    for (int i = 0; i < neutralCount; i++) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.NEUTRAL_PRESENCE,
                                neutralDef.polarity, perNeutral, null));
                    }
                }
            }
        }
    }

    /**
     * Count other subfactions in this territory that are ESTABLISHED+.
     * @param hostile If true, count hostile others. If false, count non-hostile others.
     * @param subfactionDefs Needed to derive parent-faction baseline for hostility.
     */
    private int countOtherSubfactions(String excludeId, boolean hostile,
                                      Map<String, SubfactionDef> subfactionDefs) {
        int count = 0;
        for (SubfactionPresence other : presences.values()) {
            if (other.getSubfactionId().equals(excludeId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            boolean isHostileToThem = isHostile(excludeId, other.getSubfactionId(), subfactionDefs);
            if (hostile == isHostileToThem) {
                count++;
            }
        }
        return count;
    }

    // ── Op interactions ──────────────────────────────────────────────────

    /**
     * Apply cross-op modifiers to a resolving operation based on other active ops.
     * Called just before {@code op.resolve()} to adjust effective success chance.
     *
     * <p>Current interactions:</p>
     * <ul>
     *   <li><b>WAR_RAID → PATROL</b>: Enemy WAR_RAID targeting a patrol's system halves
     *       the patrol's success chance (hunters disrupt patrols).</li>
     *   <li><b>PROTECTION_PATROL → RAID/WAR_RAID</b>: Friendly PROTECTION_PATROL
     *       active in the target system reduces raid success by 30% (protector defends).</li>
     *   <li><b>ARMS_SHIPMENT → RAID/WAR_RAID/JOINT_STRIKE</b>: Pending arms shipment
     *       targeting the resolving subfaction boosts combat op success by 30%.</li>
     * </ul>
     */
    private void applyOpInteractions(ActiveOp resolving) {
        boolean warRaidApplied = false;
        boolean protectionApplied = false;
        boolean armsApplied = false;

        for (ActiveOp other : activeOps) {
            if (other == resolving || !other.isPending()) continue;

            // WAR_RAID hunts PATROLs: halve patrol success when enemy WAR_RAID
            // targets the same system and the same subfaction.
            if (!warRaidApplied
                    && resolving.getType() == ActiveOp.OpType.PATROL
                    && other.getType() == ActiveOp.OpType.WAR_RAID
                    && !other.getSubfactionId().equals(resolving.getSubfactionId())
                    && resolving.getSubfactionId().equals(other.getTargetSubfactionId())
                    && resolving.getTargetSystemId().equals(other.getTargetSystemId())) {
                resolving.applySuccessChanceMult(0.5f);
                warRaidApplied = true;
            }

            // PROTECTION_PATROL defends against incoming raids: reduce raid
            // success when a friendly protection patrol is active in the raid's target system.
            if (!protectionApplied
                    && (resolving.getType() == ActiveOp.OpType.RAID
                        || resolving.getType() == ActiveOp.OpType.WAR_RAID)
                    && other.getType() == ActiveOp.OpType.PROTECTION_PATROL
                    && !other.getSubfactionId().equals(resolving.getSubfactionId())
                    && resolving.getTargetSystemId().equals(other.getTargetSystemId())) {
                resolving.applySuccessChanceMult(0.7f);
                protectionApplied = true;
            }

            // ARMS_SHIPMENT boosts combat ops: if the resolving subfaction is
            // receiving a pending arms shipment, its combat ops succeed more often.
            if (!armsApplied
                    && (resolving.getType() == ActiveOp.OpType.RAID
                        || resolving.getType() == ActiveOp.OpType.WAR_RAID
                        || resolving.getType() == ActiveOp.OpType.JOINT_STRIKE)
                    && other.getType() == ActiveOp.OpType.ARMS_SHIPMENT
                    && resolving.getSubfactionId().equals(other.getTargetSubfactionId())) {
                resolving.applySuccessChanceMult(1.3f);
                armsApplied = true;
            }
        }
    }

    /**
     * Generate leverage/pressure factors from a resolved operation.
     */
    private void generateOpResultFactors(ActiveOp op, IntrigueSettings settings) {
        SubfactionPresence presence = presences.get(op.getSubfactionId());
        if (presence == null) return;

        switch (op.getType()) {
            case PATROL: {
                PresenceFactor.FactorType ftype = op.getOutcome() == ActiveOp.OpOutcome.SUCCESS
                        ? PresenceFactor.FactorType.PATROL_SUCCESS
                        : PresenceFactor.FactorType.PATROL_FAILURE;
                IntrigueSettings.FactorDef def = settings.getFactorDef(ftype);
                if (def != null) {
                    presence.addFactor(new PresenceFactor(
                            ftype, def.polarity, def.getWeight(presence.getState()),
                            def.durationDays, null));
                }
                break;
            }
            case RAID: {
                // Successful raid: the TARGET gets demoted (handled in evaluateCycle).
                // Failed raid: the raiding subfaction gets a pressure factor.
                if (op.getOutcome() == ActiveOp.OpOutcome.FAILURE) {
                    IntrigueSettings.FactorDef def =
                            settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                    if (def != null) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.PATROL_FAILURE,
                                def.polarity, def.getWeight(presence.getState()),
                                def.durationDays, null));
                    }
                }
                break;
            }
            case EVACUATION: {
                // Successful evacuation: bonus leverage in demoted state (applied after demotion)
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    IntrigueSettings.FactorDef def =
                            settings.getFactorDef(PresenceFactor.FactorType.EVACUATION_BONUS);
                    if (def != null) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.EVACUATION_BONUS,
                                def.polarity, def.getWeight(presence.getState()),
                                def.durationDays, null));
                    }
                }
                break;
            }
            case EXPANSION:
            case SUPREMACY:
                // Success/failure handled by the evaluation cycle via applyPromotion/applyDemotion
                break;
            case PROTECTION_PATROL: {
                // Success: leverage factor on the Hirer (protected party)
                // Failure: pressure on Protector; check for betrayal (Hired Protection → Territorial War)
                String hirerId = op.getTargetSubfactionId();
                SubfactionPresence hirer = hirerId != null ? presences.get(hirerId) : null;
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    if (hirer != null) {
                        IntrigueSettings.FactorDef def =
                                settings.getFactorDef(PresenceFactor.FactorType.PATROL_SUCCESS);
                        if (def != null) {
                            hirer.addFactor(new PresenceFactor(
                                    PresenceFactor.FactorType.PATROL_SUCCESS,
                                    def.polarity, def.getWeight(hirer.getState()),
                                    def.durationDays, null));
                        }
                    }
                } else {
                    // Protector failed — pressure on protector
                    IntrigueSettings.FactorDef def =
                            settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                    if (def != null) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.PATROL_FAILURE,
                                def.polarity, def.getWeight(presence.getState()),
                                def.durationDays, null));
                    }
                }
                break;
            }
            case JOINT_STRIKE: {
                // Success: heavy pressure on the target (shared enemy)
                // Failure: pressure on the striking subfaction
                String targetId = op.getTargetSubfactionId();
                SubfactionPresence target = targetId != null ? presences.get(targetId) : null;
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    if (target != null) {
                        IntrigueSettings.FactorDef def =
                                settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                        if (def != null) {
                            // Weight 2 — heavier than a normal patrol failure
                            target.addFactor(new PresenceFactor(
                                    PresenceFactor.FactorType.PATROL_FAILURE,
                                    def.polarity, Math.max(2, def.getWeight(target.getState())),
                                    def.durationDays, null));
                        }
                    }
                } else {
                    IntrigueSettings.FactorDef def =
                            settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                    if (def != null) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.PATROL_FAILURE,
                                def.polarity, def.getWeight(presence.getState()),
                                def.durationDays, null));
                    }
                }
                break;
            }
            case COUNCIL:
                // Council resolution is handled by resolveCouncil(), not here.
                // Factor generation is done in resolveCouncil() based on the outcome.
                break;
            case WAR_RAID: {
                // Success: pressure on the target (enemy being hunted)
                // Failure: pressure on the attacker
                String targetId = op.getTargetSubfactionId();
                SubfactionPresence target = targetId != null ? presences.get(targetId) : null;
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    if (target != null) {
                        IntrigueSettings.FactorDef def =
                                settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                        if (def != null) {
                            target.addFactor(new PresenceFactor(
                                    PresenceFactor.FactorType.PATROL_FAILURE,
                                    def.polarity, def.getWeight(target.getState()),
                                    def.durationDays, null));
                        }
                    }
                } else {
                    IntrigueSettings.FactorDef def =
                            settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                    if (def != null) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.PATROL_FAILURE,
                                def.polarity, def.getWeight(presence.getState()),
                                def.durationDays, null));
                    }
                }
                break;
            }
            case ARMS_SHIPMENT: {
                // Success: leverage for the backed side (arms delivered).
                // Failure: pressure on the backer (shipment intercepted).
                String backedId = op.getTargetSubfactionId();
                SubfactionPresence backed = backedId != null ? presences.get(backedId) : null;
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    if (backed != null) {
                        IntrigueSettings.FactorDef def =
                                settings.getFactorDef(PresenceFactor.FactorType.PATROL_SUCCESS);
                        if (def != null) {
                            backed.addFactor(new PresenceFactor(
                                    PresenceFactor.FactorType.PATROL_SUCCESS,
                                    def.polarity, def.getWeight(backed.getState()),
                                    def.durationDays, null));
                        }
                    }
                } else {
                    IntrigueSettings.FactorDef def =
                            settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                    if (def != null) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.PATROL_FAILURE,
                                def.polarity, def.getWeight(presence.getState()),
                                def.durationDays, null));
                    }
                }
                break;
            }
            case INVASION: {
                // Success: stagnant target gets evicted, invader takes slot (handled by harness).
                // Failure: pressure factor on the stagnant target (they survived but are shaken).
                if (op.getOutcome() == ActiveOp.OpOutcome.FAILURE) {
                    String targetId = op.getTargetSubfactionId();
                    SubfactionPresence target = targetId != null ? presences.get(targetId) : null;
                    if (target != null) {
                        IntrigueSettings.FactorDef def =
                                settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
                        if (def != null) {
                            target.addFactor(new PresenceFactor(
                                    PresenceFactor.FactorType.PATROL_FAILURE,
                                    def.polarity, def.getWeight(target.getState()),
                                    def.durationDays, null));
                        }
                    }
                }
                break;
            }
        }
    }

    // ── Entanglement triggers ────────────────────────────────────────────

    /**
     * Evaluate entanglement triggers caused by a resolved op.
     * Called immediately after an op resolves and its factors are generated.
     *
     * <p>Trigger rules (initial set):</p>
     * <ul>
     *   <li><b>Successful RAID</b> → victim may seek Hired Protection from a non-hostile
     *       third party. Probability-based (50% base, higher if victim is weak).</li>
     *   <li><b>Successful RAID</b> → if a third subfaction exists that is hostile to the
     *       raider, it may form a Shared-Enemy Pact with the victim (30% base).</li>
     * </ul>
     */
    private List<TickResult> evaluateOpEntanglementTriggers(ActiveOp op,
                                                             Map<String, SubfactionDef> subfactionDefs,
                                                             IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        if (op.getType() == ActiveOp.OpType.RAID && op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
            String raiderId = op.getSubfactionId();
            String victimId = op.getTargetSubfactionId();
            if (victimId == null) return results;

            SubfactionPresence victim = presences.get(victimId);
            if (victim == null || !victim.getState().canLaunchOps()) return results;

            // Hired Protection: victim seeks a non-hostile ESTABLISHED+ subfaction
            // that is NOT the raider to act as protector
            if (opRand.nextFloat() < 0.5f) {
                String protector = findProtectorCandidate(victimId, raiderId, subfactionDefs);
                if (protector != null) {
                    SubfactionPair pair = new SubfactionPair(victimId, protector);
                    ActiveEntanglement existing = entanglements.get(pair);
                    // Only create if no entanglement already exists on this pair,
                    // or if the existing one is less important (not a war/civil war)
                    if (existing == null || (!existing.getType().setsHostile)) {
                        ActiveEntanglement hp = new ActiveEntanglement(
                                EntanglementType.HIRED_PROTECTION, pair,
                                75f, // ~60-90 day range, use midpoint
                                raiderId + " raided " + victimId);
                        results.add(applyEntanglement(hp));
                    }
                }
            }

            // Shared-Enemy Pact: a third subfaction hostile to the raider teams up with the victim
            if (opRand.nextFloat() < 0.3f) {
                String ally = findPactCandidate(victimId, raiderId, subfactionDefs);
                if (ally != null) {
                    SubfactionPair pair = new SubfactionPair(victimId, ally);
                    ActiveEntanglement existing = entanglements.get(pair);
                    if (existing == null || (!existing.getType().setsHostile)) {
                        ActiveEntanglement pact = new ActiveEntanglement(
                                EntanglementType.SHARED_ENEMY_PACT, pair,
                                -1f, // condition-based: expires when threat recedes
                                raiderId,
                                raiderId + " raided " + victimId);
                        results.add(applyEntanglement(pact));
                    }
                }
            }

            // Retaliation Coalition: a third party (not hostile to the victim, not the raider)
            // intervenes on the victim's side against an aggressive raider.
            // Differentiated from Shared-Enemy Pact: does NOT require the third party to already
            // be hostile to the raider. The aggression itself is the justification.
            // More likely when the raider is DOMINANT or has raided multiple times.
            SubfactionPresence raiderP = presences.get(raiderId);
            float retaliationChance = 0.15f;
            if (raiderP != null && raiderP.getState() == PresenceState.DOMINANT) {
                retaliationChance = 0.40f;
            }
            if (opRand.nextFloat() < retaliationChance) {
                String responder = findRetaliationResponder(victimId, raiderId, subfactionDefs);
                if (responder != null) {
                    SubfactionPair pair = new SubfactionPair(victimId, responder);
                    ActiveEntanglement existing = entanglements.get(pair);
                    if (existing == null || (!existing.getType().setsHostile)) {
                        ActiveEntanglement coalition = new ActiveEntanglement(
                                EntanglementType.RETALIATION_COALITION, pair,
                                45f, // short-duration punitive coalition
                                raiderId,
                                raiderId + " overreached against " + victimId);
                        results.add(applyEntanglement(coalition));
                    }
                }
            }
        }

        // Protection Patrol failure → betrayal: Hired Protection replaced by Territorial War
        if (op.getType() == ActiveOp.OpType.PROTECTION_PATROL
                && op.getOutcome() == ActiveOp.OpOutcome.FAILURE) {
            String protectorId = op.getSubfactionId();
            String hirerId = op.getTargetSubfactionId();
            if (hirerId != null) {
                SubfactionPair pair = new SubfactionPair(protectorId, hirerId);
                ActiveEntanglement existing = entanglements.get(pair);
                if (existing != null && existing.getType() == EntanglementType.HIRED_PROTECTION) {
                    ActiveEntanglement war = new ActiveEntanglement(
                            EntanglementType.TERRITORIAL_WAR, pair,
                            -1f,
                            "Hired Protection betrayal: " + protectorId
                                    + " failed to protect " + hirerId);
                    results.add(applyEntanglement(war));
                }
            }
        }

        // Council resolution → rewrite entanglement map
        if (op.getType() == ActiveOp.OpType.COUNCIL) {
            results.addAll(resolveCouncil(subfactionDefs));
        }

        return results;
    }

    /**
     * Evaluate entanglement triggers based on current presence states.
     * Called at the end of each tick to check for Territorial War and Civil War.
     *
     * <p>Trigger rules:</p>
     * <ul>
     *   <li><b>Territorial War</b>: Two hostile subfactions both at FORTIFIED+ with no
     *       existing entanglement (or a non-hostile entanglement) on that pair.</li>
     *   <li><b>Civil War</b>: Two same-parent subfactions both at FORTIFIED+ with no
     *       existing hostile entanglement on that pair.</li>
     * </ul>
     */
    private List<TickResult> evaluatePresenceEntanglementTriggers(
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        // Collect all FORTIFIED+ subfactions
        List<String> fortifiedPlus = new ArrayList<>();
        for (SubfactionPresence p : presences.values()) {
            if (p.getState() == PresenceState.FORTIFIED || p.getState() == PresenceState.DOMINANT) {
                fortifiedPlus.add(p.getSubfactionId());
            }
        }

        // Check all pairs of FORTIFIED+ subfactions
        for (int i = 0; i < fortifiedPlus.size(); i++) {
            for (int j = i + 1; j < fortifiedPlus.size(); j++) {
                String a = fortifiedPlus.get(i);
                String b = fortifiedPlus.get(j);
                SubfactionPair pair = new SubfactionPair(a, b);
                ActiveEntanglement existing = entanglements.get(pair);

                // Skip if they already have a hostile entanglement on this pair
                if (existing != null && existing.getType().setsHostile) continue;

                SubfactionDef defA = subfactionDefs.get(a);
                SubfactionDef defB = subfactionDefs.get(b);
                if (defA == null || defB == null) continue;

                boolean sameParent = defA.parentFactionId.equals(defB.parentFactionId);
                boolean hostile = isHostile(a, b, subfactionDefs);

                if (sameParent) {
                    // Civil War: same parent, both FORTIFIED+
                    ActiveEntanglement cw = new ActiveEntanglement(
                            EntanglementType.CIVIL_WAR, pair,
                            -1f, // condition-based: until one drops below FORTIFIED
                            "Both " + defA.name + " and " + defB.name
                                    + " reached FORTIFIED+ in " + territoryId);
                    results.add(applyEntanglement(cw));
                } else if (hostile) {
                    // Territorial War: different parents, hostile, both FORTIFIED+
                    ActiveEntanglement tw = new ActiveEntanglement(
                            EntanglementType.TERRITORIAL_WAR, pair,
                            -1f, // condition-based: until one drops below FORTIFIED
                            defA.name + " and " + defB.name
                                    + " competing for dominance in " + territoryId);
                    results.add(applyEntanglement(tw));
                }
            }
        }

        // Proxy Support: when a Territorial War is active, a third party can covertly
        // back one side. The backer must be hostile to the war target and not hostile
        // to the backed side. Checked with a random roll per eligible triplet.
        // Collect deferred applications to avoid ConcurrentModificationException.
        List<ActiveEntanglement> deferredProxies = new ArrayList<>();
        for (Map.Entry<SubfactionPair, ActiveEntanglement> entry : entanglements.entrySet()) {
            ActiveEntanglement ent = entry.getValue();
            if (ent.getType() != EntanglementType.TERRITORIAL_WAR) continue;

            String warA = entry.getKey().getFirst();
            String warB = entry.getKey().getSecond();

            for (SubfactionPresence third : presences.values()) {
                String thirdId = third.getSubfactionId();
                if (thirdId.equals(warA) || thirdId.equals(warB)) continue;
                if (!third.getState().canLaunchOps()) continue;

                // Check if the third party has a stake: hostile to one side, not hostile to the other.
                // If so, back the friendly side against the hostile side.
                String backed = null;
                String target = null;
                boolean hostileToA = isHostile(thirdId, warA, subfactionDefs);
                boolean hostileToB = isHostile(thirdId, warB, subfactionDefs);
                if (hostileToA && !hostileToB) {
                    backed = warB;
                    target = warA;
                } else if (hostileToB && !hostileToA) {
                    backed = warA;
                    target = warB;
                }

                if (backed == null) continue;

                // Only create if the backer-backed pair doesn't already have an entanglement
                SubfactionPair proxyPair = new SubfactionPair(thirdId, backed);
                if (entanglements.containsKey(proxyPair)) continue;

                // Low per-day chance so it doesn't spam
                if (opRand.nextFloat() < 0.01f) {
                    deferredProxies.add(new ActiveEntanglement(
                            EntanglementType.PROXY_SUPPORT, proxyPair,
                            60f, // ~45-60 day duration
                            target,
                            thirdId + " covertly backing " + backed + " against " + target));
                }
            }
        }
        for (ActiveEntanglement proxy : deferredProxies) {
            results.add(applyEntanglement(proxy));
        }

        // Expire condition-based entanglements whose conditions no longer hold
        List<SubfactionPair> toRemove = new ArrayList<>();
        for (Map.Entry<SubfactionPair, ActiveEntanglement> entry : entanglements.entrySet()) {
            ActiveEntanglement ent = entry.getValue();
            if (ent.isTimerBased()) continue; // timer-based expiry handled in tick step 2b

            switch (ent.getType()) {
                case TERRITORIAL_WAR:
                case CIVIL_WAR: {
                    // Expires when either participant drops below FORTIFIED
                    SubfactionPresence pA = presences.get(ent.getPair().getFirst());
                    SubfactionPresence pB = presences.get(ent.getPair().getSecond());
                    boolean aFort = pA != null && (pA.getState() == PresenceState.FORTIFIED
                            || pA.getState() == PresenceState.DOMINANT);
                    boolean bFort = pB != null && (pB.getState() == PresenceState.FORTIFIED
                            || pB.getState() == PresenceState.DOMINANT);
                    if (!aFort || !bFort) {
                        toRemove.add(entry.getKey());
                    }
                    break;
                }
                case SHARED_ENEMY_PACT: {
                    // Expires when the shared enemy (thirdPartyId) drops below FORTIFIED
                    // or is no longer present
                    String enemy = ent.getThirdPartyId();
                    if (enemy != null) {
                        SubfactionPresence enemyP = presences.get(enemy);
                        boolean threatActive = enemyP != null && enemyP.getState().canLaunchOps()
                                && (enemyP.getState() == PresenceState.FORTIFIED
                                    || enemyP.getState() == PresenceState.DOMINANT);
                        if (!threatActive) {
                            toRemove.add(entry.getKey());
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }
        for (SubfactionPair pair : toRemove) {
            ActiveEntanglement removed = entanglements.remove(pair);
            if (removed != null) {
                results.add(new TickResult(TickResultType.ENTANGLEMENT_EXPIRED, removed));
            }
        }

        return results;
    }

    /**
     * Find an ESTABLISHED+ subfaction that could serve as a hired protector for the victim.
     * Must not be the raider, must not be hostile to the victim.
     */
    private String findProtectorCandidate(String victimId, String raiderId,
                                           Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : presences.values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (!isHostile(victimId, id, subfactionDefs)) {
                return id;
            }
        }
        // No non-hostile candidate; allow a hostile one (the whole point of hired protection
        // is hiring someone who would normally be hostile)
        for (SubfactionPresence other : presences.values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            return id;
        }
        return null;
    }

    /**
     * Find an ESTABLISHED+ subfaction that is hostile to the raider and could
     * form a Shared-Enemy Pact with the victim.
     * Must not be the victim or the raider, and must be hostile to the raider.
     */
    private String findPactCandidate(String victimId, String raiderId,
                                      Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : presences.values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (isHostile(id, raiderId, subfactionDefs)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Find an ESTABLISHED+ subfaction that could respond to aggression as part of
     * a Retaliation Coalition. Must not be the victim or the raider, must not be
     * hostile to the victim, and must not already have an entanglement with the victim.
     * Unlike {@link #findPactCandidate}, does NOT require hostility to the raider —
     * the aggression itself justifies the intervention.
     */
    private String findRetaliationResponder(String victimId, String raiderId,
                                             Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : presences.values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (isHostile(victimId, id, subfactionDefs)) continue;
            // Prefer subfactions that don't already have an entanglement with the victim
            SubfactionPair pair = new SubfactionPair(victimId, id);
            if (entanglements.containsKey(pair)) continue;
            return id;
        }
        return null;
    }

    // ── Entanglement-spawned ops ─────────────────────────────────────────

    /** Interval between entanglement-spawned op launches for the same pair. */
    private static final float ENTANGLEMENT_OP_INTERVAL_DAYS = 30f;

    /**
     * Check active entanglements and launch their fleet ops when cooldowns allow.
     *
     * <ul>
     *   <li><b>Hired Protection</b> → Protector launches PROTECTION_PATROL to Hirer's system.</li>
     *   <li><b>Shared-Enemy Pact</b> → Both pact members launch JOINT_STRIKE against the shared enemy.</li>
     *   <li><b>Retaliation Coalition</b> → Both members launch JOINT_STRIKE against the aggressor.</li>
     * </ul>
     */
    private List<TickResult> launchEntanglementOps(Map<String, SubfactionDef> subfactionDefs,
                                                    IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        // Tick cooldowns
        for (Map.Entry<SubfactionPair, Float> entry : entanglementOpCooldowns.entrySet()) {
            if (entry.getValue() > 0f) {
                entry.setValue(entry.getValue() - 1f);
            }
        }

        for (Map.Entry<SubfactionPair, ActiveEntanglement> entry : entanglements.entrySet()) {
            SubfactionPair pair = entry.getKey();
            ActiveEntanglement ent = entry.getValue();

            // Check cooldown
            Float cooldown = entanglementOpCooldowns.get(pair);
            if (cooldown != null && cooldown > 0f) continue;

            switch (ent.getType()) {
                case HIRED_PROTECTION:
                    results.addAll(launchProtectionPatrol(pair, ent, settings));
                    break;
                case SHARED_ENEMY_PACT:
                case RETALIATION_COALITION:
                    results.addAll(launchJointStrike(pair, ent, subfactionDefs, settings));
                    break;
                case TERRITORIAL_WAR:
                case CIVIL_WAR:
                    results.addAll(launchWarRaids(pair, ent, subfactionDefs, settings));
                    break;
                case PROXY_SUPPORT:
                    results.addAll(launchArmsShipment(pair, ent, settings));
                    break;
                default:
                    break;
            }
        }

        // Clean up cooldowns for expired entanglements
        entanglementOpCooldowns.keySet().retainAll(entanglements.keySet());

        return results;
    }

    /**
     * Launch a Protection Patrol for a Hired Protection entanglement.
     * The Protector patrols the Hirer's base system.
     */
    private List<TickResult> launchProtectionPatrol(SubfactionPair pair,
                                                     ActiveEntanglement ent,
                                                     IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        // Determine roles: the hirer is the one under threat, the protector is
        // the one providing protection. The trigger reason records who raided whom;
        // as a heuristic, pick the first member of the pair that is weaker (lower state ordinal).
        String first = pair.getFirst();
        String second = pair.getSecond();
        SubfactionPresence pFirst = presences.get(first);
        SubfactionPresence pSecond = presences.get(second);
        if (pFirst == null || pSecond == null) return results;
        if (!pFirst.getState().canLaunchOps() || !pSecond.getState().canLaunchOps()) return results;

        // Hirer = weaker (lower state ordinal), Protector = stronger
        String hirerId, protectorId;
        if (pFirst.getState().ordinal() <= pSecond.getState().ordinal()) {
            hirerId = first;
            protectorId = second;
        } else {
            hirerId = second;
            protectorId = first;
        }

        // Don't stack protection patrols
        if (countActiveOps(protectorId, ActiveOp.OpType.PROTECTION_PATROL) > 0) return results;

        String hirerSystem = findBaseSystem(hirerId);
        String protectorSystem = findBaseSystem(protectorId);
        if (hirerSystem == null || protectorSystem == null) return results;

        float duration = settings.getOpDuration("PROTECTION_PATROL", 30f);
        float chance = settings.getOpSuccessChance("PROTECTION_PATROL", 0.80f);
        ActiveOp op = new ActiveOp(
                ActiveOp.OpType.PROTECTION_PATROL, protectorId,
                protectorSystem, hirerSystem,
                duration, chance);
        op.setTargetSubfactionId(hirerId);
        activeOps.add(op);
        entanglementOpCooldowns.put(pair, ENTANGLEMENT_OP_INTERVAL_DAYS);
        results.add(new TickResult(TickResultType.OP_LAUNCHED, protectorId, op));
        return results;
    }

    /**
     * Launch Joint Strike(s) for a Shared-Enemy Pact entanglement.
     * Both pact members attack the shared enemy's base system.
     */
    private List<TickResult> launchJointStrike(SubfactionPair pair,
                                                ActiveEntanglement ent,
                                                Map<String, SubfactionDef> subfactionDefs,
                                                IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        String enemyId = ent.getThirdPartyId();
        if (enemyId == null) return results;

        SubfactionPresence enemy = presences.get(enemyId);
        if (enemy == null || !enemy.getState().canLaunchOps()) return results;

        String enemySystem = findBaseSystem(enemyId);
        if (enemySystem == null) return results;

        // Both pact members launch a JOINT_STRIKE if they can
        for (String memberId : new String[]{pair.getFirst(), pair.getSecond()}) {
            SubfactionPresence member = presences.get(memberId);
            if (member == null || !member.getState().canLaunchOps()) continue;
            if (countActiveOps(memberId, ActiveOp.OpType.JOINT_STRIKE) > 0) continue;

            String memberSystem = findBaseSystem(memberId);
            if (memberSystem == null) continue;

            float duration = settings.getOpDuration("JOINT_STRIKE", 25f);
            float chance = settings.getOpSuccessChance("JOINT_STRIKE", 0.55f);
            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.JOINT_STRIKE, memberId,
                    memberSystem, enemySystem,
                    duration, chance);
            op.setTargetSubfactionId(enemyId);
            activeOps.add(op);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, memberId, op));
        }

        if (!results.isEmpty()) {
            entanglementOpCooldowns.put(pair, ENTANGLEMENT_OP_INTERVAL_DAYS);
        }
        return results;
    }

    /**
     * Launch War Raids for a Territorial War (or Civil War) entanglement.
     * Each side sends a combat fleet to hunt the other side's active patrols.
     * Target system = enemy's active patrol target system (if any), else enemy base system.
     */
    private List<TickResult> launchWarRaids(SubfactionPair pair,
                                             ActiveEntanglement ent,
                                             Map<String, SubfactionDef> subfactionDefs,
                                             IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        for (String attackerId : new String[]{pair.getFirst(), pair.getSecond()}) {
            String defenderId = pair.other(attackerId);

            SubfactionPresence attacker = presences.get(attackerId);
            SubfactionPresence defender = presences.get(defenderId);
            if (attacker == null || !attacker.getState().canLaunchOps()) continue;
            if (defender == null || !defender.getState().canLaunchOps()) continue;

            // Don't stack war raids from same attacker against same defender
            if (countActiveOps(attackerId, ActiveOp.OpType.WAR_RAID) > 0) continue;

            String attackerSystem = findBaseSystem(attackerId);
            if (attackerSystem == null) continue;

            // Target the system where the enemy has an active patrol, if any
            String targetSystem = findActivePatrolSystem(defenderId);
            if (targetSystem == null) {
                targetSystem = findBaseSystem(defenderId);
            }
            if (targetSystem == null) continue;

            float duration = settings.getOpDuration("WAR_RAID", 30f);
            float chance = settings.getOpSuccessChance("WAR_RAID", 0.60f);
            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.WAR_RAID, attackerId,
                    attackerSystem, targetSystem,
                    duration, chance);
            op.setTargetSubfactionId(defenderId);
            activeOps.add(op);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, attackerId, op));
        }

        if (!results.isEmpty()) {
            entanglementOpCooldowns.put(pair, ENTANGLEMENT_OP_INTERVAL_DAYS);
        }
        return results;
    }

    /**
     * Launch an Arms Shipment for a Proxy Support entanglement.
     * The backer (first of pair) covertly ships arms to the backed subfaction.
     * While in transit, the backed side's combat ops get a success boost via
     * {@link #applyOpInteractions}. On success: leverage for backed side.
     * On failure: pressure on backer (shipment intercepted).
     */
    private List<TickResult> launchArmsShipment(SubfactionPair pair,
                                                 ActiveEntanglement ent,
                                                 IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        // Pair = (backer, backed).
        String backerId = pair.getFirst();
        String backedId = pair.getSecond();

        SubfactionPresence backer = presences.get(backerId);
        SubfactionPresence backed = presences.get(backedId);
        if (backer == null || !backer.getState().canLaunchOps()) return results;
        if (backed == null || !backed.getState().canLaunchOps()) return results;

        // Don't stack arms shipments from same backer
        if (countActiveOps(backerId, ActiveOp.OpType.ARMS_SHIPMENT) > 0) return results;

        String backerSystem = findBaseSystem(backerId);
        String backedSystem = findBaseSystem(backedId);
        if (backerSystem == null || backedSystem == null) return results;

        float duration = settings.getOpDuration("ARMS_SHIPMENT", 30f);
        float chance = settings.getOpSuccessChance("ARMS_SHIPMENT", 0.70f);
        ActiveOp op = new ActiveOp(
                ActiveOp.OpType.ARMS_SHIPMENT, backerId,
                backerSystem, backedSystem,
                duration, chance);
        op.setTargetSubfactionId(backedId);
        activeOps.add(op);
        entanglementOpCooldowns.put(pair, ENTANGLEMENT_OP_INTERVAL_DAYS);
        results.add(new TickResult(TickResultType.OP_LAUNCHED, backerId, op));
        return results;
    }

    /** Find the target system of an active PATROL for the given subfaction, or null. */
    private String findActivePatrolSystem(String subfactionId) {
        for (ActiveOp op : activeOps) {
            if (op.getType() == ActiveOp.OpType.PATROL
                    && op.getSubfactionId().equals(subfactionId)
                    && op.isPending()) {
                return op.getTargetSystemId();
            }
        }
        return null;
    }

    // ── Council op ───────────────────────────────────────────────────────

    /** Council outcome types. */
    public enum CouncilOutcome {
        /** Most hostile entanglements dissolved, cooling-off period. */
        DETENTE,
        /** 1–3 pairs form cooperative entanglements, others unchanged. */
        SELECTIVE_PACTS,
        /** Nothing changes — diplomatic gridlock. */
        STATUS_QUO,
        /** 1–2 new hostile entanglements form between pairs previously at peace. */
        BREAKDOWN,
        /** All pairs go hostile — total political collapse. */
        CATASTROPHIC_BREAKDOWN
    }

    /**
     * Check if a council should be triggered. Councils fire at regular intervals
     * as a "shaker" for territory dynamics. Conditions:
     * <ul>
     *   <li>Cooldown elapsed (≥120 days since last council)</li>
     *   <li>No council already in progress</li>
     *   <li>≥3 ESTABLISHED+ subfactions (enough participants for negotiation)</li>
     * </ul>
     */
    private List<TickResult> evaluateCouncilTrigger(Map<String, SubfactionDef> subfactionDefs,
                                                     IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        // Don't stack councils
        if (countActiveOps(null, ActiveOp.OpType.COUNCIL) > 0) return results;

        // Count ESTABLISHED+ subfactions
        int establishedPlus = 0;
        for (SubfactionPresence p : presences.values()) {
            if (p.getState().canLaunchOps()) establishedPlus++;
        }

        // Need at least 3 participants
        if (establishedPlus < 3) return results;

        // Launch a council op — use the first ESTABLISHED+ subfaction as the "convener"
        String convenerId = null;
        for (SubfactionPresence p : presences.values()) {
            if (p.getState().canLaunchOps()) {
                convenerId = p.getSubfactionId();
                break;
            }
        }
        if (convenerId == null) return results;

        String system = findBaseSystem(convenerId);
        if (system == null) system = systemIds.get(0);

        float duration = settings.getOpDuration("COUNCIL", 20f);
        // Council always "succeeds" — the outcome is rolled in resolution
        ActiveOp op = new ActiveOp(ActiveOp.OpType.COUNCIL, convenerId,
                system, system, duration, 1.0f);
        activeOps.add(op);
        daysSinceLastCouncil = 0f;
        results.add(new TickResult(TickResultType.OP_LAUNCHED, convenerId, op));
        return results;
    }

    /**
     * Resolve a council op by rolling an outcome and rewriting entanglements.
     * Called from evaluateOpEntanglementTriggers when a COUNCIL op resolves.
     *
     * @return TickResults for all entanglement changes.
     */
    private List<TickResult> resolveCouncil(Map<String, SubfactionDef> subfactionDefs) {
        List<TickResult> results = new ArrayList<>();

        // Count hostile entanglements and participants to weight outcomes
        int hostileCount = 0;
        int establishedPlus = 0;
        for (ActiveEntanglement ent : entanglements.values()) {
            if (ent.getType().setsHostile) hostileCount++;
        }
        for (SubfactionPresence p : presences.values()) {
            if (p.getState().canLaunchOps()) establishedPlus++;
        }

        // Calculate territory tension level as ratio of hostile pairs to total pairs
        int totalPairs = (establishedPlus * (establishedPlus - 1)) / 2;
        float hostileRatio = (totalPairs > 0) ? Math.min(1f, (float) hostileCount / totalPairs) : 0f;

        // Bias toward change: peaceful territories get disrupted, warlike ones calm down
        float pacificationBias = hostileRatio;
        float disruptionBias = 1f - hostileRatio;

        // Roll outcome — weighted to act as a "shaker" for territory dynamics
        float roll = opRand.nextFloat();
        CouncilOutcome outcome;

        float detenteWeight        = 0.05f + 0.30f * pacificationBias;  // 0.05 – 0.35
        float selectivePactsWeight = 0.10f + 0.20f * pacificationBias;  // 0.10 – 0.30
        float breakdownWeight      = 0.15f + 0.30f * disruptionBias;    // 0.15 – 0.45
        float catastropheWeight    = 0.05f + 0.15f * disruptionBias;    // 0.05 – 0.20
        // Status quo is the remainder — always possible but minority outcome
        float statusQuoWeight      = 1f - detenteWeight - selectivePactsWeight
                                       - breakdownWeight - catastropheWeight;

        if (roll < detenteWeight) {
            outcome = CouncilOutcome.DETENTE;
        } else if (roll < detenteWeight + selectivePactsWeight) {
            outcome = CouncilOutcome.SELECTIVE_PACTS;
        } else if (roll < detenteWeight + selectivePactsWeight + statusQuoWeight) {
            outcome = CouncilOutcome.STATUS_QUO;
        } else if (roll < detenteWeight + selectivePactsWeight + statusQuoWeight + breakdownWeight) {
            outcome = CouncilOutcome.BREAKDOWN;
        } else {
            outcome = CouncilOutcome.CATASTROPHIC_BREAKDOWN;
        }

        switch (outcome) {
            case DETENTE: {
                // Dissolve most hostile entanglements
                List<SubfactionPair> toRemove = new ArrayList<>();
                for (Map.Entry<SubfactionPair, ActiveEntanglement> e : entanglements.entrySet()) {
                    if (e.getValue().getType().setsHostile) {
                        toRemove.add(e.getKey());
                    }
                }
                for (SubfactionPair pair : toRemove) {
                    ActiveEntanglement removed = entanglements.remove(pair);
                    if (removed != null) {
                        results.add(new TickResult(TickResultType.ENTANGLEMENT_EXPIRED, removed));
                    }
                }
                break;
            }
            case SELECTIVE_PACTS: {
                // Form 1-2 cooperative entanglements between hostile pairs
                List<SubfactionPair> hostilePairs = new ArrayList<>();
                for (Map.Entry<SubfactionPair, ActiveEntanglement> e : entanglements.entrySet()) {
                    if (e.getValue().getType().setsHostile) {
                        hostilePairs.add(e.getKey());
                    }
                }
                int pactsToForm = Math.min(1 + (opRand.nextFloat() < 0.5f ? 1 : 0), hostilePairs.size());
                Collections.shuffle(hostilePairs, opRand);
                for (int i = 0; i < pactsToForm; i++) {
                    SubfactionPair pair = hostilePairs.get(i);
                    ActiveEntanglement pact = new ActiveEntanglement(
                            EntanglementType.HIRED_PROTECTION, pair,
                            90f, // negotiated pacts last longer
                            "Council negotiation in " + territoryId);
                    results.add(applyEntanglement(pact));
                }
                break;
            }
            case STATUS_QUO:
                // Nothing changes — but the council still happened (logged)
                break;
            case BREAKDOWN: {
                // 1-2 new hostile entanglements between pairs that are NOT already hostile
                List<String> established = new ArrayList<>();
                for (SubfactionPresence p : presences.values()) {
                    if (p.getState().canLaunchOps()) established.add(p.getSubfactionId());
                }
                int formed = 0;
                int maxNew = 1 + (opRand.nextFloat() < 0.4f ? 1 : 0);
                for (int i = 0; i < established.size() && formed < maxNew; i++) {
                    for (int j = i + 1; j < established.size() && formed < maxNew; j++) {
                        SubfactionPair pair = new SubfactionPair(established.get(i), established.get(j));
                        ActiveEntanglement existing = entanglements.get(pair);
                        if (existing != null && existing.getType().setsHostile) continue;
                        if (!isHostile(established.get(i), established.get(j), subfactionDefs)) {
                            ActiveEntanglement war = new ActiveEntanglement(
                                    EntanglementType.TERRITORIAL_WAR, pair,
                                    120f, // council-created: timer-based, not condition-based
                                    "Council breakdown in " + territoryId);
                            results.add(applyEntanglement(war));
                            formed++;
                        }
                    }
                }
                break;
            }
            case CATASTROPHIC_BREAKDOWN: {
                // Every non-hostile pair gets a Territorial War
                List<String> established = new ArrayList<>();
                for (SubfactionPresence p : presences.values()) {
                    if (p.getState().canLaunchOps()) established.add(p.getSubfactionId());
                }
                for (int i = 0; i < established.size(); i++) {
                    for (int j = i + 1; j < established.size(); j++) {
                        SubfactionPair pair = new SubfactionPair(established.get(i), established.get(j));
                        ActiveEntanglement existing = entanglements.get(pair);
                        if (existing != null && existing.getType().setsHostile) continue;
                        ActiveEntanglement war = new ActiveEntanglement(
                                EntanglementType.TERRITORIAL_WAR, pair,
                                90f, // council-created: timer-based, shorter for catastrophic
                                "Catastrophic council breakdown in " + territoryId);
                        results.add(applyEntanglement(war));
                    }
                }
                break;
            }
        }

        return results;
    }

    // ── Evaluation cycle ─────────────────────────────────────────────────

    /**
     * Run the periodic evaluation cycle. Checks all subfactions for promotion/demotion
     * eligibility and triggers ops or direct transitions.
     *
     * Returns up to one demotion and one promotion result per cycle.
     */
    private List<TickResult> evaluateCycle(Map<String, SubfactionDef> subfactionDefs,
                                           IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        String demotionCandidate = null;
        int worstPressure = 0;
        String promotionCandidate = null;
        int bestLeverage = 0;

        for (SubfactionPresence presence : presences.values()) {
            if (!presence.getState().canLaunchOps()) continue;

            int net = presence.getNetBalance();

            // Demotion: net balance is negative, and magnitude exceeds threshold
            if (net < 0 && (-net) >= settings.transitionThreshold) {
                if ((-net) > worstPressure) {
                    worstPressure = -net;
                    demotionCandidate = presence.getSubfactionId();
                }
            }

            // Promotion: net balance is positive, magnitude exceeds threshold, not already DOMINANT
            if (net > 0 && net >= settings.transitionThreshold
                    && presence.getState() != PresenceState.DOMINANT) {
                if (net > bestLeverage) {
                    bestLeverage = net;
                    promotionCandidate = presence.getSubfactionId();
                }
            }
        }

        // Trigger demotion op
        if (demotionCandidate != null) {
            results.addAll(triggerDemotionOp(demotionCandidate, subfactionDefs, settings));
        }

        // Trigger promotion op
        if (promotionCandidate != null) {
            results.addAll(triggerPromotionOp(promotionCandidate, subfactionDefs, settings));
        }

        return results;
    }

    /**
     * Trigger a demotion op for the given subfaction.
     * If a hostile subfaction exists in-territory, it launches a RAID.
     * Otherwise, the subfaction itself launches an EVACUATION.
     */
    private List<TickResult> triggerDemotionOp(String targetId,
                                                Map<String, SubfactionDef> subfactionDefs,
                                                IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();
        SubfactionPresence target = presences.get(targetId);
        if (target == null) return results;

        String originSystem = findBaseSystem(targetId);
        if (originSystem == null) return results;

        // Find a hostile subfaction to launch a raid
        String raider = findRaider(targetId, subfactionDefs);

        if (raider != null) {
            // Hostile raid: raider attacks the target
            String raiderOrigin = findBaseSystem(raider);
            if (raiderOrigin == null) raiderOrigin = originSystem;

            float duration = settings.getOpDuration("RAID", 30f);
            float chance = settings.getOpSuccessChance("RAID", 0.5f);
            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.RAID, raider,
                    raiderOrigin, originSystem,
                    duration, chance);
            op.setTargetSubfactionId(targetId);
            activeOps.add(op);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, raider, op));
        } else {
            // No hostile — voluntary evacuation
            String targetSystem = pickPatrolTarget(targetId, originSystem);
            if (targetSystem == null) targetSystem = originSystem;

            float duration = settings.getOpDuration("EVACUATION", 20f);
            float chance = settings.getOpSuccessChance("EVACUATION", 0.8f);
            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.EVACUATION, targetId,
                    originSystem, targetSystem,
                    duration, chance);
            activeOps.add(op);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, targetId, op));
        }

        return results;
    }

    /**
     * Trigger a promotion op for the given subfaction.
     */
    private List<TickResult> triggerPromotionOp(String subfactionId,
                                                 Map<String, SubfactionDef> subfactionDefs,
                                                 IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();
        SubfactionPresence presence = presences.get(subfactionId);
        if (presence == null) return results;

        String originSystem = findBaseSystem(subfactionId);
        if (originSystem == null) return results;

        String targetSystem = pickPatrolTarget(subfactionId, originSystem);
        if (targetSystem == null) targetSystem = originSystem;

        ActiveOp.OpType opType;
        String opTypeName;
        if (presence.getState() == PresenceState.ESTABLISHED) {
            opType = ActiveOp.OpType.EXPANSION;
            opTypeName = "EXPANSION";
        } else {
            opType = ActiveOp.OpType.SUPREMACY;
            opTypeName = "SUPREMACY";
        }

        float duration = settings.getOpDuration(opTypeName, 35f);
        float chance = settings.getOpSuccessChance(opTypeName, 0.5f);
        ActiveOp op = new ActiveOp(opType, subfactionId,
                originSystem, targetSystem, duration, chance);
        activeOps.add(op);
        results.add(new TickResult(TickResultType.OP_LAUNCHED, subfactionId, op));

        return results;
    }

    /**
     * Check for stagnant ESTABLISHED subfactions and launch invasions from outside.
     * A subfaction at ESTABLISHED for ≥ STAGNATION_INVASION_DAYS gets targeted by
     * an external subfaction not currently in this territory. After the check fires
     * (invasion launched or no invader available), the timer resets.
     */
    private List<TickResult> evaluateStagnationInvasions(
            Map<String, SubfactionDef> subfactionDefs, IntrigueSettings settings) {
        List<TickResult> results = new ArrayList<>();

        for (SubfactionPresence presence : presences.values()) {
            if (presence.getState() != PresenceState.ESTABLISHED) continue;
            if (presence.getDaysSinceStateChange() < STAGNATION_INVASION_DAYS) continue;

            // Already has an INVASION targeting this subfaction — skip
            boolean alreadyTargeted = false;
            for (ActiveOp op : activeOps) {
                if (op.getType() == ActiveOp.OpType.INVASION
                        && presence.getSubfactionId().equals(op.getTargetSubfactionId())) {
                    alreadyTargeted = true;
                    break;
                }
            }
            if (alreadyTargeted) continue;

            // Find an invader: subfaction not in this territory, preferring hostile ones
            String invaderId = pickInvader(presence.getSubfactionId(), subfactionDefs);
            if (invaderId == null) {
                // No invader available — reset timer and try again next period
                presence.setState(PresenceState.ESTABLISHED);
                continue;
            }

            // Pick a system for the invasion to target (the stagnant subfaction's base)
            String targetSystem = findBaseSystem(presence.getSubfactionId());
            if (targetSystem == null) {
                presence.setState(PresenceState.ESTABLISHED);
                continue;
            }

            // Reset the stagnant subfaction's timer so the next check is a full period later
            presence.setState(PresenceState.ESTABLISHED);

            // Launch the invasion
            String originSystem = systemIds.get(opRand.nextInt(systemIds.size()));
            float duration = settings.getOpDuration("INVASION", 45f);
            float chance = settings.getOpSuccessChance("INVASION", 0.45f);
            ActiveOp op = new ActiveOp(
                    ActiveOp.OpType.INVASION, invaderId,
                    originSystem, targetSystem,
                    duration, chance);
            op.setTargetSubfactionId(presence.getSubfactionId());
            activeOps.add(op);
            results.add(new TickResult(TickResultType.OP_LAUNCHED, invaderId, op));
        }

        return results;
    }

    /**
     * Pick an invading subfaction from the global pool that is NOT in this territory.
     * Prefers subfactions hostile to the target; falls back to any outsider.
     */
    private String pickInvader(String targetId, Map<String, SubfactionDef> subfactionDefs) {
        List<String> hostile = new ArrayList<>();
        List<String> neutral = new ArrayList<>();

        for (String candidateId : subfactionDefs.keySet()) {
            if (presences.containsKey(candidateId)) continue; // already in territory

            if (isHostile(candidateId, targetId, subfactionDefs)) {
                hostile.add(candidateId);
            } else {
                neutral.add(candidateId);
            }
        }

        if (!hostile.isEmpty()) return hostile.get(opRand.nextInt(hostile.size()));
        if (!neutral.isEmpty()) return neutral.get(opRand.nextInt(neutral.size()));
        return null;
    }

    /** Find another ESTABLISHED+ subfaction that is hostile to the target, or null. */
    private String findRaider(String targetId, Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : presences.values()) {
            if (other.getSubfactionId().equals(targetId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (isHostile(other.getSubfactionId(), targetId, subfactionDefs)) {
                return other.getSubfactionId();
            }
        }
        return null;
    }

    /**
     * Apply a promotion transition to the given subfaction.
     * Called when a promotion op (EXPANSION/SUPREMACY) succeeds.
     *
     * @return TickResult describing the promotion, or null if promotion not applicable.
     */
    public TickResult applyPromotion(String subfactionId) {
        SubfactionPresence presence = presences.get(subfactionId);
        if (presence == null) return null;

        PresenceState oldState = presence.getState();
        PresenceState newState = null;
        switch (oldState) {
            case ESTABLISHED: newState = PresenceState.FORTIFIED; break;
            case FORTIFIED:   newState = PresenceState.DOMINANT; break;
            default: return null;
        }

        presence.setState(newState);
        return new TickResult(TickResultType.PRESENCE_PROMOTED, subfactionId, oldState, newState);
    }

    /**
     * Apply a demotion transition to the given subfaction.
     * Called when a demotion op (RAID/EVACUATION) resolves.
     *
     * @return TickResult describing the demotion, or null if demotion not applicable.
     */
    public TickResult applyDemotion(String subfactionId) {
        SubfactionPresence presence = presences.get(subfactionId);
        if (presence == null) return null;

        PresenceState oldState = presence.getState();
        PresenceState newState = null;
        switch (oldState) {
            case DOMINANT:    newState = PresenceState.FORTIFIED; break;
            case FORTIFIED:   newState = PresenceState.ESTABLISHED; break;
            case ESTABLISHED: newState = PresenceState.NONE; break;
            default: return null;
        }

        if (newState == PresenceState.NONE) {
            evictSubfaction(subfactionId);
        } else {
            presence.setState(newState);
        }

        return new TickResult(TickResultType.PRESENCE_DEMOTED, subfactionId, oldState, newState);
    }

    /**
     * Fully remove a subfaction from this territory: release base slot, cancel
     * active ops, remove entanglements, and delete the presence entry.
     */
    private void evictSubfaction(String subfactionId) {
        // Release base slot
        for (BaseSlot slot : baseSlots) {
            if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                slot.release();
                break;
            }
        }

        // Cancel active ops belonging to this subfaction or targeting it
        activeOps.removeIf(op ->
                subfactionId.equals(op.getSubfactionId()) ||
                subfactionId.equals(op.getTargetSubfactionId()));

        // Remove entanglements involving this subfaction
        entanglements.entrySet().removeIf(e ->
                e.getKey().contains(subfactionId));

        // Remove cooldowns
        patrolCooldowns.remove(subfactionId);
        entanglementOpCooldowns.entrySet().removeIf(e ->
                e.getKey().contains(subfactionId));

        // Remove the presence itself
        presences.remove(subfactionId);
        establishmentThresholds.remove(subfactionId);
    }

    /**
     * Apply a successful invasion: evict the target subfaction and install the invader
     * as ESTABLISHED, taking over the target's base slot.
     *
     * @param invaderId The external subfaction that won the invasion.
     * @param targetId  The stagnant subfaction being replaced.
     * @param invaderDef SubfactionDef for the invader (needed for establishment threshold).
     * @return A list of tick results: eviction + optional establishment.
     */
    public List<TickResult> applyInvasion(String invaderId, String targetId,
                                           SubfactionDef invaderDef) {
        List<TickResult> results = new ArrayList<>();

        // Find the target's base slot before eviction
        BaseSlot targetSlot = null;
        for (BaseSlot slot : baseSlots) {
            if (slot.isOccupied() && targetId.equals(slot.getOccupiedBySubfactionId())) {
                targetSlot = slot;
                break;
            }
        }

        // Evict the target
        evictSubfaction(targetId);
        results.add(new TickResult(TickResultType.SUBFACTION_EVICTED, targetId,
                PresenceState.ESTABLISHED, PresenceState.NONE));

        // Install the invader as ESTABLISHED, claiming the target's old slot
        if (invaderDef != null) {
            addPresence(invaderDef, PresenceState.ESTABLISHED);
            if (targetSlot != null) {
                targetSlot.claim(invaderId);
                targetSlot.setStationEntityId(null); // cleared for re-spawning
            }
        }

        return results;
    }

    // ── Evaluation state ─────────────────────────────────────────────────

    public float getDaysUntilEvaluation() {
        return daysUntilEvaluation;
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
            if (op.getType() == type && (subfactionId == null || op.getSubfactionId().equals(subfactionId))) {
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

        // Create empty states and decide target counts (3–5 each)
        Map<String, TerritoryState> states = new LinkedHashMap<>();
        Map<String, Integer> targetCounts = new LinkedHashMap<>();
        for (String tid : territoryIds) {
            states.put(tid, new TerritoryState(tid, territories.get(tid)));
            targetCounts.put(tid, 3 + rand.nextInt(3));
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
