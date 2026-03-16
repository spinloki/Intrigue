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

    private static final long serialVersionUID = 7L;

    // ── Constants ────────────────────────────────────────────────────────



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

    /** Monotonically increasing op ID counter. Serialized to survive save/load. */
    private long nextOpId = 1;

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
        this.daysSinceLastCouncil = 120f; // eligible immediately (will use settings.councilCooldownDays at runtime)
        this.dynamicHostilities = new LinkedHashMap<>();
    }

    /** Generate the next unique op ID for this territory. Survives save/load. */
    long nextOpId() {
        return nextOpId++;
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
     * Delegates to {@link HostilityResolver#isHostile}.
     */
    public boolean isHostile(String a, String b, Map<String, SubfactionDef> subfactionDefs) {
        return HostilityResolver.isHostile(a, b, entanglements, dynamicHostilities, subfactionDefs);
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
     * Create (or replace) an entanglement and return the appropriate TickResult.
     * Convenience for code that needs to emit the result into a results list.
     */
    public TickResult applyEntanglement(ActiveEntanglement entanglement) {
        ActiveEntanglement previous = setEntanglement(entanglement);
        if (previous != null) {
            return new TickResult.EntanglementReplaced(entanglement, previous);
        }
        return new TickResult.EntanglementCreated(entanglement);
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
        SUBFACTION_EVICTED,
        /** A new subfaction arrived in the territory (NONE → SCOUTING). */
        SUBFACTION_ARRIVED
    }

    /**
     * Result of a daily tick evaluation.
     * The caller (TerritoryManager or test harness) inspects this to decide
     * what side effects to execute.
     *
     * <p>Sealed interface — each result type is a record carrying only the
     * fields relevant to that event. Use {@link #type()} for switch dispatch
     * and cast to the specific record to access typed fields.</p>
     */
    public sealed interface TickResult {
        TickResultType type();

        /** Subfaction ID relevant to this result, or null for entanglement/hostility events. */
        default String subfactionId() { return null; }

        record EstablishBase(String subfactionId, BaseSlot slot) implements TickResult {
            public TickResultType type() { return TickResultType.ESTABLISH_BASE; }
        }

        record OpLaunched(String subfactionId, ActiveOp op) implements TickResult {
            public TickResultType type() { return TickResultType.OP_LAUNCHED; }
        }

        record OpResolved(String subfactionId, ActiveOp op) implements TickResult {
            public TickResultType type() { return TickResultType.OP_RESOLVED; }
        }

        record PresencePromoted(String subfactionId, PresenceState oldState,
                                PresenceState newState) implements TickResult {
            public TickResultType type() { return TickResultType.PRESENCE_PROMOTED; }
        }

        record PresenceDemoted(String subfactionId, PresenceState oldState,
                               PresenceState newState) implements TickResult {
            public TickResultType type() { return TickResultType.PRESENCE_DEMOTED; }
        }

        record EntanglementCreated(ActiveEntanglement entanglement) implements TickResult {
            public TickResultType type() { return TickResultType.ENTANGLEMENT_CREATED; }
        }

        record EntanglementExpired(ActiveEntanglement entanglement) implements TickResult {
            public TickResultType type() { return TickResultType.ENTANGLEMENT_EXPIRED; }
        }

        record EntanglementReplaced(ActiveEntanglement newEntanglement,
                                    ActiveEntanglement replaced) implements TickResult {
            public TickResultType type() { return TickResultType.ENTANGLEMENT_REPLACED; }
        }

        record HostilitiesStarted(SubfactionPair parentPair) implements TickResult {
            public TickResultType type() { return TickResultType.HOSTILITIES_STARTED; }
        }

        record HostilitiesEnded(SubfactionPair parentPair) implements TickResult {
            public TickResultType type() { return TickResultType.HOSTILITIES_ENDED; }
        }

        record SubfactionEvicted(String subfactionId) implements TickResult {
            public TickResultType type() { return TickResultType.SUBFACTION_EVICTED; }
        }

        record SubfactionArrived(String subfactionId) implements TickResult {
            public TickResultType type() { return TickResultType.SUBFACTION_ARRIVED; }
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

        tickPresencesAndDesertions(results, subfactionDefs, settings);
        recalculateAndExpireEntanglements(results, subfactionDefs, settings);
        resolveCompletedOps(results, subfactionDefs, settings);
        launchPatrolOps(results, settings);
        results.addAll(EntanglementEngine.launchEntanglementOps(this, subfactionDefs, settings));
        rollDesertionEvents(settings);
        runEvaluationCycle(results, subfactionDefs, settings);
        checkCouncilAndEntanglements(results, subfactionDefs, settings);

        return results;
    }

    /** Phase 1: Advance presence timers, tick factors, resolve expired desertions. */
    private void tickPresencesAndDesertions(List<TickResult> results,
                                            Map<String, SubfactionDef> subfactionDefs,
                                            IntrigueSettings settings) {
        for (SubfactionPresence presence : presences.values()) {
            presence.advanceDays(1f);
            List<PresenceFactor> expired = presence.tickFactors();

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
    }

    /** Phase 2: Recalculate factors, expire entanglements and dynamic hostilities. */
    private void recalculateAndExpireEntanglements(List<TickResult> results,
                                                    Map<String, SubfactionDef> subfactionDefs,
                                                    IntrigueSettings settings) {
        recalculateFactors(settings, subfactionDefs);

        Iterator<Map.Entry<SubfactionPair, ActiveEntanglement>> entIt =
                entanglements.entrySet().iterator();
        while (entIt.hasNext()) {
            ActiveEntanglement ent = entIt.next().getValue();
            if (ent.advanceDay()) {
                entIt.remove();
                results.add(new TickResult.EntanglementExpired(ent));
            }
        }

        results.addAll(HostilityResolver.tickDynamicHostilities(
                dynamicHostilities, presences, subfactionDefs, opRand, settings));
    }

    /** Phase 3: Resolve completed operations and generate factors from results. */
    private void resolveCompletedOps(List<TickResult> results,
                                     Map<String, SubfactionDef> subfactionDefs,
                                     IntrigueSettings settings) {
        Iterator<ActiveOp> it = activeOps.iterator();
        while (it.hasNext()) {
            ActiveOp op = it.next();
            if (op.advanceDay()) {
                if (op.isPending()) {
                    OpEngine.applyOpInteractions(op, activeOps, settings);
                    op.resolve(opRand);
                }
                results.add(new TickResult.OpResolved(op.getSubfactionId(), op));
                OpEngine.generateOpResultFactors(op, settings, presences);
                results.addAll(EntanglementEngine.evaluateOpEntanglementTriggers(
                        op, this, subfactionDefs, settings));
                it.remove();
            }
        }
    }

    /** Phase 4: Launch new patrol ops for eligible subfactions. */
    private void launchPatrolOps(List<TickResult> results, IntrigueSettings settings) {
        for (SubfactionPresence presence : presences.values()) {
            if (!presence.getState().canLaunchOps()) continue;

            String sid = presence.getSubfactionId();

            Float cooldown = patrolCooldowns.get(sid);
            if (cooldown != null && cooldown > 0f) {
                patrolCooldowns.put(sid, cooldown - 1f);
                continue;
            }

            int maxPatrols = maxConcurrentPatrols(presence.getState());
            if (countActiveOps(sid, ActiveOp.OpType.PATROL) >= maxPatrols) continue;

            String originSystem = findBaseSystem(sid);
            if (originSystem == null) continue;

            String targetSystem = pickPatrolTarget(sid, originSystem);
            if (targetSystem == null) continue;

            float duration = settings.getOpDuration("PATROL", 45f);
            float chance = settings.getOpSuccessChance("PATROL", 0.75f);
            ActiveOp op = new ActiveOp(nextOpId(),
                    ActiveOp.OpType.PATROL, sid,
                    originSystem, targetSystem,
                    duration, chance);
            activeOps.add(op);
            patrolCooldowns.put(sid, settings.patrolIntervalDays);
            results.add(new TickResult.OpLaunched(sid, op));
        }
    }

    /** Phase 5: Roll for new desertion events. */
    private void rollDesertionEvents(IntrigueSettings settings) {
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
    }

    /** Phase 6: Evaluation cycle — check for promotion/demotion/stagnation/arrivals. */
    private void runEvaluationCycle(List<TickResult> results,
                                    Map<String, SubfactionDef> subfactionDefs,
                                    IntrigueSettings settings) {
        daysUntilEvaluation -= 1f;
        if (daysUntilEvaluation <= 0f) {
            daysUntilEvaluation = settings.evaluationIntervalDays;
            results.addAll(OpEngine.evaluateCycle(this, subfactionDefs, settings));
            checkSubfactionArrivals(results, subfactionDefs, settings);
        }
        results.addAll(OpEngine.evaluateStagnationInvasions(this, subfactionDefs, settings));
    }

    /**
     * Check if the territory is underpopulated and roll for new subfaction arrivals.
     * For each vacancy (target - current count), roll arrivalChancePerVacancy.
     * Pick from subfactions not currently present in this territory.
     */
    private void checkSubfactionArrivals(List<TickResult> results,
                                         Map<String, SubfactionDef> subfactionDefs,
                                         IntrigueSettings settings) {
        int currentCount = presences.size();
        int target = settings.arrivalTargetCount;
        if (currentCount >= target) return;

        // Build candidate list: subfactions not already present
        List<SubfactionDef> candidates = new ArrayList<>();
        for (SubfactionDef def : subfactionDefs.values()) {
            if (!presences.containsKey(def.id)) {
                candidates.add(def);
            }
        }
        if (candidates.isEmpty()) return;

        // Shuffle for fairness
        Collections.shuffle(candidates, opRand);

        int vacancies = target - currentCount;
        int arrivals = 0;
        for (int i = 0; i < vacancies && arrivals < candidates.size(); i++) {
            if (opRand.nextFloat() < settings.arrivalChancePerVacancy) {
                SubfactionDef def = candidates.get(arrivals);
                addPresence(def, PresenceState.SCOUTING);
                results.add(new TickResult.SubfactionArrived(def.id));
                arrivals++;
            }
        }
    }

    /** Phase 7: Council trigger check and presence-based entanglement triggers. */
    private void checkCouncilAndEntanglements(List<TickResult> results,
                                              Map<String, SubfactionDef> subfactionDefs,
                                              IntrigueSettings settings) {
        daysSinceLastCouncil += 1f;
        if (daysSinceLastCouncil >= settings.councilCooldownDays) {
            results.addAll(EntanglementEngine.evaluateCouncilTrigger(
                    this, subfactionDefs, settings));
        }
        results.addAll(EntanglementEngine.evaluatePresenceEntanglementTriggers(
                this, subfactionDefs, settings));
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
        float quellChance = settings.desertionBaseQuellChance + net * settings.desertionNetBalanceModifier;
        quellChance = Math.max(settings.desertionMinQuellChance, Math.min(settings.desertionMaxQuellChance, quellChance));

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
                    int hostileCount = HostilityResolver.countOtherSubfactions(presence.getSubfactionId(), true, presences, entanglements, dynamicHostilities, subfactionDefs);
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
                    int neutralCount = HostilityResolver.countOtherSubfactions(presence.getSubfactionId(), false, presences, entanglements, dynamicHostilities, subfactionDefs);
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
     * Apply a promotion transition to the given subfaction.
     * Called when a promotion op (EXPANSION/SUPREMACY) succeeds.
     *
     * @return TickResult describing the promotion, or null if promotion not applicable.
     */
    public TickResult.PresencePromoted applyPromotion(String subfactionId) {
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
        return new TickResult.PresencePromoted(subfactionId, oldState, newState);
    }

    /**
     * Apply a demotion transition to the given subfaction.
     * Called when a demotion op (RAID/EVACUATION) resolves.
     *
     * @return TickResult describing the demotion, or null if demotion not applicable.
     */
    public TickResult.PresenceDemoted applyDemotion(String subfactionId) {
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

        return new TickResult.PresenceDemoted(subfactionId, oldState, newState);
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
        results.add(new TickResult.SubfactionEvicted(targetId));

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

                return new TickResult.EstablishBase(presence.getSubfactionId(), slot);
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
    int countActiveOps(String subfactionId, ActiveOp.OpType type) {
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
    String findBaseSystem(String subfactionId) {
        for (BaseSlot slot : baseSlots) {
            if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                return slot.getSystemId();
            }
        }
        return null;
    }

    /** Pick a patrol target system (different from origin). */
    String pickPatrolTarget(String subfactionId, String originSystem) {
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

    // ── Package-private accessors (for engine classes) ─────────────────

    Map<String, SubfactionPresence> presencesMutable() { return presences; }
    List<ActiveOp> activeOpsMutable() { return activeOps; }
    Map<SubfactionPair, ActiveEntanglement> entanglementsMutable() { return entanglements; }
    Map<SubfactionPair, Float> dynamicHostilitiesMutable() { return dynamicHostilities; }
    Map<SubfactionPair, Float> entanglementOpCooldownsMutable() { return entanglementOpCooldowns; }
    Random opRand() { return opRand; }
    void resetCouncilTimer() { daysSinceLastCouncil = 0f; }

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
