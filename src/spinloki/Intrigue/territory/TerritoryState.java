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

    private static final long serialVersionUID = 3L;

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
        OP_RESOLVED,
        /** A subfaction was promoted to a higher presence state. */
        PRESENCE_PROMOTED,
        /** A subfaction was demoted to a lower presence state. */
        PRESENCE_DEMOTED
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

        /** ESTABLISH_BASE result. */
        public TickResult(String subfactionId, BaseSlot slotToEstablish) {
            this.type = TickResultType.ESTABLISH_BASE;
            this.subfactionId = subfactionId;
            this.shouldEstablishBase = true;
            this.slotToEstablish = slotToEstablish;
            this.op = null;
            this.oldState = null;
            this.newState = null;
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
        recalculateFactors(settings);

        // 3. Resolve completed operations and generate factors from results
        Iterator<ActiveOp> it = activeOps.iterator();
        while (it.hasNext()) {
            ActiveOp op = it.next();
            if (op.advanceDay()) {
                // Timer expired — resolve probabilistically (unless already overridden)
                if (op.isPending()) {
                    op.resolve(opRand);
                }
                results.add(new TickResult(TickResultType.OP_RESOLVED, op.getSubfactionId(), op));
                generateOpResultFactors(op, settings);
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
    private void recalculateFactors(IntrigueSettings settings) {
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
                    int hostileCount = countOtherSubfactions(presence.getSubfactionId(), true);
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
                    int neutralCount = countOtherSubfactions(presence.getSubfactionId(), false);
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
     * @param hostile If true, count all others (they're all potentially hostile).
     *               If false, count only non-hostile (neutral).
     *               For now, all other subfactions are treated as hostile in the
     *               pure-Java layer. The Starsector layer can refine this using
     *               actual faction relationships.
     */
    private int countOtherSubfactions(String excludeId, boolean hostile) {
        int count = 0;
        for (SubfactionPresence other : presences.values()) {
            if (other.getSubfactionId().equals(excludeId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            // In the pure-Java layer, treat all others as hostile for simplicity.
            // The Starsector layer can distinguish hostile vs neutral.
            if (hostile) {
                count++;
            }
        }
        return count;
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
        }
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
        String raider = findRaider(targetId);

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

    /** Find another ESTABLISHED+ subfaction to be the raider, or null. */
    private String findRaider(String targetId) {
        for (SubfactionPresence other : presences.values()) {
            if (other.getSubfactionId().equals(targetId)) continue;
            if (other.getState().canLaunchOps()) return other.getSubfactionId();
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
            case ESTABLISHED: newState = PresenceState.SCOUTING; break;
            default: return null;
        }

        presence.setState(newState);

        // If demoted to SCOUTING, lose the base
        if (newState == PresenceState.SCOUTING) {
            for (BaseSlot slot : baseSlots) {
                if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                    slot.release();
                    break;
                }
            }
        }

        return new TickResult(TickResultType.PRESENCE_DEMOTED, subfactionId, oldState, newState);
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
            targetCounts.put(tid, 3 + rand.nextInt(2));
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
