package spinloki.Intrigue.territory;

import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.util.*;

/**
 * Op lifecycle engine: launch, resolve, cross-op interactions, factor generation,
 * and the periodic evaluation cycle (promotion/demotion/invasion).
 *
 * <p>All methods are static and operate on data passed from {@link TerritoryState}.
 * No mutable state is held here.</p>
 */
final class OpEngine {

    private OpEngine() {} // utility class

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
    static void applyOpInteractions(ActiveOp resolving, List<ActiveOp> activeOps,
                                     IntrigueSettings settings) {
        boolean warRaidApplied = false;
        boolean protectionApplied = false;
        boolean armsApplied = false;

        for (ActiveOp other : activeOps) {
            if (other == resolving || !other.isPending()) continue;

            if (!warRaidApplied
                    && resolving.getType() == ActiveOp.OpType.PATROL
                    && other.getType() == ActiveOp.OpType.WAR_RAID
                    && !other.getSubfactionId().equals(resolving.getSubfactionId())
                    && resolving.getSubfactionId().equals(other.getTargetSubfactionId())
                    && resolving.getTargetSystemId().equals(other.getTargetSystemId())) {
                resolving.applySuccessChanceMult(settings.warRaidPatrolDisruption);
                warRaidApplied = true;
            }

            if (!protectionApplied
                    && (resolving.getType() == ActiveOp.OpType.RAID
                        || resolving.getType() == ActiveOp.OpType.WAR_RAID)
                    && other.getType() == ActiveOp.OpType.PROTECTION_PATROL
                    && !other.getSubfactionId().equals(resolving.getSubfactionId())
                    && resolving.getTargetSystemId().equals(other.getTargetSystemId())) {
                resolving.applySuccessChanceMult(settings.protectionPatrolDefense);
                protectionApplied = true;
            }

            if (!armsApplied
                    && (resolving.getType() == ActiveOp.OpType.RAID
                        || resolving.getType() == ActiveOp.OpType.WAR_RAID
                        || resolving.getType() == ActiveOp.OpType.JOINT_STRIKE)
                    && other.getType() == ActiveOp.OpType.ARMS_SHIPMENT
                    && resolving.getSubfactionId().equals(other.getTargetSubfactionId())) {
                resolving.applySuccessChanceMult(settings.armsShipmentBoost);
                armsApplied = true;
            }
        }
    }

    /**
     * Generate leverage/pressure factors from a resolved operation.
     */
    static void generateOpResultFactors(ActiveOp op, IntrigueSettings settings,
                                        Map<String, SubfactionPresence> presences) {
        SubfactionPresence presence = presences.get(op.getSubfactionId());
        if (presence == null) return;

        switch (op.getType()) {
            case PATROL:
                generatePatrolFactors(op, settings, presence);
                break;
            case RAID:
                if (op.getOutcome() == ActiveOp.OpOutcome.FAILURE) {
                    applyCombatSetback(settings, presence, 0);
                }
                break;
            case EVACUATION:
                generateEvacuationFactors(op, settings, presence);
                break;
            case EXPANSION:
            case SUPREMACY:
                break;
            case PROTECTION_PATROL:
                generateProtectionPatrolFactors(op, settings, presence, presences);
                break;
            case JOINT_STRIKE:
                generateJointStrikeFactors(op, settings, presence, presences);
                break;
            case COUNCIL:
                break;
            case WAR_RAID:
                generateWarRaidFactors(op, settings, presence, presences);
                break;
            case ARMS_SHIPMENT:
                generateArmsShipmentFactors(op, settings, presence, presences);
                break;
            case INVASION:
                if (op.getOutcome() == ActiveOp.OpOutcome.FAILURE) {
                    SubfactionPresence target = resolveTarget(op, presences);
                    if (target != null) applyCombatSetback(settings, target, 0);
                }
                break;
        }
    }

    /** Apply a COMBAT_SETBACK factor to the given presence. minWeight overrides if > 0. */
    private static void applyCombatSetback(IntrigueSettings settings,
                                           SubfactionPresence target, int minWeight) {
        IntrigueSettings.FactorDef def =
                settings.getFactorDef(PresenceFactor.FactorType.COMBAT_SETBACK);
        if (def != null) {
            int w = def.getWeight(target.getState());
            if (minWeight > 0) w = Math.max(minWeight, w);
            target.addFactor(new PresenceFactor(
                    PresenceFactor.FactorType.COMBAT_SETBACK,
                    def.polarity, w, def.durationDays, null));
        }
    }

    /** Resolve the target subfaction presence from an op's targetSubfactionId. */
    private static SubfactionPresence resolveTarget(ActiveOp op,
                                                     Map<String, SubfactionPresence> presences) {
        String targetId = op.getTargetSubfactionId();
        return targetId != null ? presences.get(targetId) : null;
    }

    private static void generatePatrolFactors(ActiveOp op, IntrigueSettings settings,
                                              SubfactionPresence presence) {
        PresenceFactor.FactorType ftype = op.getOutcome() == ActiveOp.OpOutcome.SUCCESS
                ? PresenceFactor.FactorType.PATROL_SUCCESS
                : PresenceFactor.FactorType.PATROL_FAILURE;
        IntrigueSettings.FactorDef def = settings.getFactorDef(ftype);
        if (def != null) {
            presence.addFactor(new PresenceFactor(
                    ftype, def.polarity, def.getWeight(presence.getState()),
                    def.durationDays, null));
        }
    }

    private static void generateEvacuationFactors(ActiveOp op, IntrigueSettings settings,
                                                   SubfactionPresence presence) {
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
    }

    private static void generateProtectionPatrolFactors(ActiveOp op, IntrigueSettings settings,
                                                         SubfactionPresence presence,
                                                         Map<String, SubfactionPresence> presences) {
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
            IntrigueSettings.FactorDef def =
                    settings.getFactorDef(PresenceFactor.FactorType.PATROL_FAILURE);
            if (def != null) {
                presence.addFactor(new PresenceFactor(
                        PresenceFactor.FactorType.PATROL_FAILURE,
                        def.polarity, def.getWeight(presence.getState()),
                        def.durationDays, null));
            }
        }
    }

    private static void generateJointStrikeFactors(ActiveOp op, IntrigueSettings settings,
                                                    SubfactionPresence presence,
                                                    Map<String, SubfactionPresence> presences) {
        if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
            SubfactionPresence target = resolveTarget(op, presences);
            if (target != null) applyCombatSetback(settings, target, 2);
        } else {
            applyCombatSetback(settings, presence, 0);
        }
    }

    private static void generateWarRaidFactors(ActiveOp op, IntrigueSettings settings,
                                                SubfactionPresence presence,
                                                Map<String, SubfactionPresence> presences) {
        if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
            SubfactionPresence target = resolveTarget(op, presences);
            if (target != null) applyCombatSetback(settings, target, 0);
        } else {
            applyCombatSetback(settings, presence, 0);
        }
    }

    private static void generateArmsShipmentFactors(ActiveOp op, IntrigueSettings settings,
                                                     SubfactionPresence presence,
                                                     Map<String, SubfactionPresence> presences) {
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
            applyCombatSetback(settings, presence, 0);
        }
    }

    /**
     * Run the periodic evaluation cycle. Checks all subfactions for promotion/demotion
     * eligibility and triggers ops or direct transitions.
     */
    static List<TerritoryState.TickResult> evaluateCycle(TerritoryState state,
                                                         Map<String, SubfactionDef> subfactionDefs,
                                                         IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        String demotionCandidate = null;
        int worstPressure = 0;
        String promotionCandidate = null;
        int bestLeverage = 0;

        for (SubfactionPresence presence : state.presencesMutable().values()) {
            if (!presence.getState().canLaunchOps()) continue;

            int net = presence.getNetBalance();

            if (net < 0 && (-net) >= settings.transitionThreshold) {
                if ((-net) > worstPressure) {
                    worstPressure = -net;
                    demotionCandidate = presence.getSubfactionId();
                }
            }

            if (net > 0 && net >= settings.transitionThreshold
                    && presence.getState() != PresenceState.DOMINANT) {
                if (net > bestLeverage) {
                    bestLeverage = net;
                    promotionCandidate = presence.getSubfactionId();
                }
            }
        }

        if (demotionCandidate != null) {
            results.addAll(triggerDemotionOp(state, demotionCandidate, subfactionDefs, settings));
        }

        if (promotionCandidate != null) {
            results.addAll(triggerPromotionOp(state, promotionCandidate, settings));
        }

        return results;
    }

    /**
     * Trigger a demotion op for the given subfaction.
     * If a hostile subfaction exists in-territory, it launches a RAID.
     * Otherwise, the subfaction itself launches an EVACUATION.
     */
    static List<TerritoryState.TickResult> triggerDemotionOp(
            TerritoryState state, String targetId,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
        SubfactionPresence target = state.presencesMutable().get(targetId);
        if (target == null) return results;

        String originSystem = state.findBaseSystem(targetId);
        if (originSystem == null) return results;

        String raider = findRaider(targetId, state, subfactionDefs);

        if (raider != null) {
            String raiderOrigin = state.findBaseSystem(raider);
            if (raiderOrigin == null) raiderOrigin = originSystem;

            float duration = settings.getOpDuration("RAID", 30f);
            float chance = settings.getOpSuccessChance("RAID", 0.5f);
            ActiveOp op = new ActiveOp(state.nextOpId(),
                    ActiveOp.OpType.RAID, raider,
                    raiderOrigin, originSystem,
                    duration, chance);
            op.setTargetSubfactionId(targetId);
            state.activeOpsMutable().add(op);
            results.add(new TerritoryState.TickResult.OpLaunched(raider, op));
        } else {
            String targetSystem = state.pickPatrolTarget(targetId, originSystem);
            if (targetSystem == null) targetSystem = originSystem;

            float duration = settings.getOpDuration("EVACUATION", 20f);
            float chance = settings.getOpSuccessChance("EVACUATION", 0.8f);
            ActiveOp op = new ActiveOp(state.nextOpId(),
                    ActiveOp.OpType.EVACUATION, targetId,
                    originSystem, targetSystem,
                    duration, chance);
            state.activeOpsMutable().add(op);
            results.add(new TerritoryState.TickResult.OpLaunched(targetId, op));
        }

        return results;
    }

    /**
     * Trigger a promotion op for the given subfaction.
     */
    static List<TerritoryState.TickResult> triggerPromotionOp(
            TerritoryState state, String subfactionId,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
        SubfactionPresence presence = state.presencesMutable().get(subfactionId);
        if (presence == null) return results;

        String originSystem = state.findBaseSystem(subfactionId);
        if (originSystem == null) return results;

        String targetSystem = state.pickPatrolTarget(subfactionId, originSystem);
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
        ActiveOp op = new ActiveOp(state.nextOpId(), opType, subfactionId,
                originSystem, targetSystem, duration, chance);
        state.activeOpsMutable().add(op);
        results.add(new TerritoryState.TickResult.OpLaunched(subfactionId, op));

        return results;
    }

    /**
     * Check for stagnant ESTABLISHED subfactions and launch invasions from outside.
     */
    static List<TerritoryState.TickResult> evaluateStagnationInvasions(
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        for (SubfactionPresence presence : state.presencesMutable().values()) {
            if (presence.getState() != PresenceState.ESTABLISHED) continue;
            if (presence.getDaysSinceStateChange() < settings.stagnationInvasionDays) continue;

            boolean alreadyTargeted = false;
            for (ActiveOp op : state.activeOpsMutable()) {
                if (op.getType() == ActiveOp.OpType.INVASION
                        && presence.getSubfactionId().equals(op.getTargetSubfactionId())) {
                    alreadyTargeted = true;
                    break;
                }
            }
            if (alreadyTargeted) continue;

            String invaderId = pickInvader(presence.getSubfactionId(), state, subfactionDefs);
            if (invaderId == null) {
                presence.setState(PresenceState.ESTABLISHED);
                continue;
            }

            String targetSystem = state.findBaseSystem(presence.getSubfactionId());
            if (targetSystem == null) {
                presence.setState(PresenceState.ESTABLISHED);
                continue;
            }

            presence.setState(PresenceState.ESTABLISHED);

            List<String> systemIds = state.getSystemIds();
            String originSystem = systemIds.get(state.opRand().nextInt(systemIds.size()));
            float duration = settings.getOpDuration("INVASION", 45f);
            float chance = settings.getOpSuccessChance("INVASION", 0.45f);
            ActiveOp op = new ActiveOp(state.nextOpId(),
                    ActiveOp.OpType.INVASION, invaderId,
                    originSystem, targetSystem,
                    duration, chance);
            op.setTargetSubfactionId(presence.getSubfactionId());
            state.activeOpsMutable().add(op);
            results.add(new TerritoryState.TickResult.OpLaunched(invaderId, op));
        }

        return results;
    }

    /**
     * Pick an invading subfaction from the global pool that is NOT in this territory.
     * Prefers subfactions hostile to the target; falls back to any outsider.
     */
    static String pickInvader(String targetId, TerritoryState state,
                              Map<String, SubfactionDef> subfactionDefs) {
        List<String> hostile = new ArrayList<>();
        List<String> neutral = new ArrayList<>();

        for (String candidateId : subfactionDefs.keySet()) {
            if (state.presencesMutable().containsKey(candidateId)) continue;

            if (state.isHostile(candidateId, targetId, subfactionDefs)) {
                hostile.add(candidateId);
            } else {
                neutral.add(candidateId);
            }
        }

        Random opRand = state.opRand();
        if (!hostile.isEmpty()) return hostile.get(opRand.nextInt(hostile.size()));
        if (!neutral.isEmpty()) return neutral.get(opRand.nextInt(neutral.size()));
        return null;
    }

    /** Find another ESTABLISHED+ subfaction that is hostile to the target, or null. */
    static String findRaider(String targetId, TerritoryState state,
                             Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : state.presencesMutable().values()) {
            if (other.getSubfactionId().equals(targetId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (state.isHostile(other.getSubfactionId(), targetId, subfactionDefs)) {
                return other.getSubfactionId();
            }
        }
        return null;
    }
}
