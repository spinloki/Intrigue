package spinloki.Intrigue.territory;

import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.util.*;

/**
 * Entanglement lifecycle engine: trigger evaluation, expiry, council resolution,
 * and entanglement-spawned fleet ops (Protection Patrol, Joint Strike, War Raid,
 * Arms Shipment).
 *
 * <p>All methods are static and operate on data passed from {@link TerritoryState}.
 * No mutable state is held here.</p>
 */
final class EntanglementEngine {

    private EntanglementEngine() {} // utility class



    /** Council outcome types. */
    enum CouncilOutcome {
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

    // ── Op-triggered entanglements ───────────────────────────────────────

    /**
     * Evaluate entanglement triggers caused by a resolved op.
     * Called immediately after an op resolves and its factors are generated.
     */
    static List<TerritoryState.TickResult> evaluateOpEntanglementTriggers(
            ActiveOp op, TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        if (op.getType() == ActiveOp.OpType.RAID && op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
            String raiderId = op.getSubfactionId();
            String victimId = op.getTargetSubfactionId();
            if (victimId == null) return results;

            SubfactionPresence victim = state.presencesMutable().get(victimId);
            if (victim == null || !victim.getState().canLaunchOps()) return results;

            Random opRand = state.opRand();
            Map<SubfactionPair, ActiveEntanglement> entanglements = state.entanglementsMutable();

            // Hired Protection: victim seeks a non-hostile protector
            if (opRand.nextFloat() < settings.hiredProtectionChance) {
                String protector = findProtectorCandidate(victimId, raiderId, state, subfactionDefs);
                if (protector != null) {
                    SubfactionPair pair = new SubfactionPair(victimId, protector);
                    ActiveEntanglement existing = entanglements.get(pair);
                    if (existing == null || (!existing.getType().setsHostile)) {
                        ActiveEntanglement hp = new ActiveEntanglement(
                                EntanglementType.HIRED_PROTECTION, pair,
                                settings.hiredProtectionDurationDays, null, victimId,
                                raiderId + " raided " + victimId);
                        results.add(state.applyEntanglement(hp));
                    }
                }
            }

            // Shared-Enemy Pact
            if (opRand.nextFloat() < settings.sharedEnemyPactChance) {
                String ally = findPactCandidate(victimId, raiderId, state, subfactionDefs);
                if (ally != null) {
                    SubfactionPair pair = new SubfactionPair(victimId, ally);
                    ActiveEntanglement existing = entanglements.get(pair);
                    if (existing == null || (!existing.getType().setsHostile)) {
                        ActiveEntanglement pact = new ActiveEntanglement(
                                EntanglementType.SHARED_ENEMY_PACT, pair,
                                -1f, raiderId,
                                raiderId + " raided " + victimId);
                        results.add(state.applyEntanglement(pact));
                    }
                }
            }

            // Retaliation Coalition
            SubfactionPresence raiderP = state.presencesMutable().get(raiderId);
            float retaliationChance = settings.retaliationBaseChance;
            if (raiderP != null && raiderP.getState() == PresenceState.DOMINANT) {
                retaliationChance = settings.retaliationDominantChance;
            }
            if (opRand.nextFloat() < retaliationChance) {
                String responder = findRetaliationResponder(victimId, raiderId, state, subfactionDefs);
                if (responder != null) {
                    SubfactionPair pair = new SubfactionPair(victimId, responder);
                    ActiveEntanglement existing = entanglements.get(pair);
                    if (existing == null || (!existing.getType().setsHostile)) {
                        ActiveEntanglement coalition = new ActiveEntanglement(
                                EntanglementType.RETALIATION_COALITION, pair,
                                settings.retaliationDurationDays, raiderId,
                                raiderId + " overreached against " + victimId);
                        results.add(state.applyEntanglement(coalition));
                    }
                }
            }
        }

        // Protection Patrol failure → betrayal
        if (op.getType() == ActiveOp.OpType.PROTECTION_PATROL
                && op.getOutcome() == ActiveOp.OpOutcome.FAILURE) {
            String protectorId = op.getSubfactionId();
            String hirerId = op.getTargetSubfactionId();
            if (hirerId != null) {
                SubfactionPair pair = new SubfactionPair(protectorId, hirerId);
                ActiveEntanglement existing = state.entanglementsMutable().get(pair);
                if (existing != null && existing.getType() == EntanglementType.HIRED_PROTECTION) {
                    ActiveEntanglement war = new ActiveEntanglement(
                            EntanglementType.TERRITORIAL_WAR, pair,
                            -1f,
                            "Hired Protection betrayal: " + protectorId
                                    + " failed to protect " + hirerId);
                    results.add(state.applyEntanglement(war));
                }
            }
        }

        // Council resolution
        if (op.getType() == ActiveOp.OpType.COUNCIL) {
            results.addAll(resolveCouncil(state, subfactionDefs, settings));
        }

        return results;
    }

    // ── Presence-triggered entanglements ──────────────────────────────────

    /**
     * Evaluate entanglement triggers based on current presence states.
     * Called at the end of each tick to check for Territorial War, Civil War,
     * Proxy Support, and condition-based expiry.
     */
    static List<TerritoryState.TickResult> evaluatePresenceEntanglementTriggers(
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
        Map<SubfactionPair, ActiveEntanglement> entanglements = state.entanglementsMutable();
        Map<String, SubfactionPresence> presences = state.presencesMutable();
        Random opRand = state.opRand();

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

                if (existing != null && existing.getType().setsHostile) continue;

                SubfactionDef defA = subfactionDefs.get(a);
                SubfactionDef defB = subfactionDefs.get(b);
                if (defA == null || defB == null) continue;

                boolean sameParent = defA.parentFactionId.equals(defB.parentFactionId);
                boolean hostile = state.isHostile(a, b, subfactionDefs);

                if (sameParent) {
                    ActiveEntanglement cw = new ActiveEntanglement(
                            EntanglementType.CIVIL_WAR, pair,
                            -1f,
                            "Both " + defA.name + " and " + defB.name
                                    + " reached FORTIFIED+ in " + state.getTerritoryId());
                    results.add(state.applyEntanglement(cw));
                } else if (hostile) {
                    ActiveEntanglement tw = new ActiveEntanglement(
                            EntanglementType.TERRITORIAL_WAR, pair,
                            -1f,
                            defA.name + " and " + defB.name
                                    + " competing for dominance in " + state.getTerritoryId());
                    results.add(state.applyEntanglement(tw));
                }
            }
        }

        // Proxy Support
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

                String backed = null;
                String target = null;
                boolean hostileToA = state.isHostile(thirdId, warA, subfactionDefs);
                boolean hostileToB = state.isHostile(thirdId, warB, subfactionDefs);
                if (hostileToA && !hostileToB) {
                    backed = warB;
                    target = warA;
                } else if (hostileToB && !hostileToA) {
                    backed = warA;
                    target = warB;
                }

                if (backed == null) continue;

                SubfactionPair proxyPair = new SubfactionPair(thirdId, backed);
                if (entanglements.containsKey(proxyPair)) continue;

                if (opRand.nextFloat() < settings.proxySupportDailyChance) {
                    deferredProxies.add(new ActiveEntanglement(
                            EntanglementType.PROXY_SUPPORT, proxyPair,
                            settings.proxySupportDurationDays, target,
                            thirdId + " covertly backing " + backed + " against " + target));
                }
            }
        }
        for (ActiveEntanglement proxy : deferredProxies) {
            results.add(state.applyEntanglement(proxy));
        }

        // Expire condition-based entanglements whose conditions no longer hold
        List<SubfactionPair> toRemove = new ArrayList<>();
        for (Map.Entry<SubfactionPair, ActiveEntanglement> entry : entanglements.entrySet()) {
            ActiveEntanglement ent = entry.getValue();
            if (ent.isTimerBased()) continue;

            switch (ent.getType()) {
                case TERRITORIAL_WAR:
                case CIVIL_WAR: {
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
                results.add(new TerritoryState.TickResult.EntanglementExpired(removed));
            }
        }

        return results;
    }

    // ── Candidate finders ────────────────────────────────────────────────

    static String findProtectorCandidate(String victimId, String raiderId,
                                         TerritoryState state,
                                         Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : state.presencesMutable().values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (!state.isHostile(victimId, id, subfactionDefs)) {
                return id;
            }
        }
        for (SubfactionPresence other : state.presencesMutable().values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            return id;
        }
        return null;
    }

    static String findPactCandidate(String victimId, String raiderId,
                                     TerritoryState state,
                                     Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : state.presencesMutable().values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (state.isHostile(id, raiderId, subfactionDefs)) {
                return id;
            }
        }
        return null;
    }

    static String findRetaliationResponder(String victimId, String raiderId,
                                            TerritoryState state,
                                            Map<String, SubfactionDef> subfactionDefs) {
        for (SubfactionPresence other : state.presencesMutable().values()) {
            String id = other.getSubfactionId();
            if (id.equals(victimId) || id.equals(raiderId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            if (state.isHostile(victimId, id, subfactionDefs)) continue;
            SubfactionPair pair = new SubfactionPair(victimId, id);
            if (state.entanglementsMutable().containsKey(pair)) continue;
            return id;
        }
        return null;
    }

    // ── Entanglement-spawned ops ─────────────────────────────────────────

    /**
     * Check active entanglements and launch their fleet ops when cooldowns allow.
     */
    static List<TerritoryState.TickResult> launchEntanglementOps(
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
        Map<SubfactionPair, Float> cooldowns = state.entanglementOpCooldownsMutable();

        // Tick cooldowns
        for (Map.Entry<SubfactionPair, Float> entry : cooldowns.entrySet()) {
            if (entry.getValue() > 0f) {
                entry.setValue(entry.getValue() - 1f);
            }
        }

        for (Map.Entry<SubfactionPair, ActiveEntanglement> entry :
                state.entanglementsMutable().entrySet()) {
            SubfactionPair pair = entry.getKey();
            ActiveEntanglement ent = entry.getValue();

            Float cooldown = cooldowns.get(pair);
            if (cooldown != null && cooldown > 0f) continue;

            switch (ent.getType()) {
                case HIRED_PROTECTION:
                    results.addAll(launchProtectionPatrol(pair, ent, state, settings));
                    break;
                case SHARED_ENEMY_PACT:
                case RETALIATION_COALITION:
                    results.addAll(launchJointStrike(pair, ent, state, subfactionDefs, settings));
                    break;
                case TERRITORIAL_WAR:
                case CIVIL_WAR:
                    results.addAll(launchWarRaids(pair, ent, state, subfactionDefs, settings));
                    break;
                case PROXY_SUPPORT:
                    results.addAll(launchArmsShipment(pair, ent, state, settings));
                    break;
                default:
                    break;
            }
        }

        // Clean up cooldowns for expired entanglements
        cooldowns.keySet().retainAll(state.entanglementsMutable().keySet());

        return results;
    }

    private static List<TerritoryState.TickResult> launchProtectionPatrol(
            SubfactionPair pair, ActiveEntanglement ent,
            TerritoryState state, IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        String hirerId = ent.getHirerId();
        if (hirerId == null) {
            SubfactionPresence pFirst = state.presencesMutable().get(pair.getFirst());
            SubfactionPresence pSecond = state.presencesMutable().get(pair.getSecond());
            if (pFirst == null || pSecond == null) return results;
            hirerId = pFirst.getState().ordinal() <= pSecond.getState().ordinal()
                    ? pair.getFirst() : pair.getSecond();
        }
        String protectorId = pair.other(hirerId);

        SubfactionPresence pHirer = state.presencesMutable().get(hirerId);
        SubfactionPresence pProtector = state.presencesMutable().get(protectorId);
        if (pHirer == null || pProtector == null) return results;
        if (!pHirer.getState().canLaunchOps() || !pProtector.getState().canLaunchOps()) return results;

        if (state.countActiveOps(protectorId, ActiveOp.OpType.PROTECTION_PATROL) > 0) return results;

        String hirerSystem = state.findBaseSystem(hirerId);
        String protectorSystem = state.findBaseSystem(protectorId);
        if (hirerSystem == null || protectorSystem == null) return results;

        float duration = settings.getOpDuration("PROTECTION_PATROL", 30f);
        float chance = settings.getOpSuccessChance("PROTECTION_PATROL", 0.80f);
        ActiveOp op = new ActiveOp(state.nextOpId(),
                ActiveOp.OpType.PROTECTION_PATROL, protectorId,
                protectorSystem, hirerSystem,
                duration, chance);
        op.setTargetSubfactionId(hirerId);
        state.activeOpsMutable().add(op);
        state.entanglementOpCooldownsMutable().put(pair, settings.entanglementOpIntervalDays);
        results.add(new TerritoryState.TickResult.OpLaunched(protectorId, op));
        return results;
    }

    private static List<TerritoryState.TickResult> launchJointStrike(
            SubfactionPair pair, ActiveEntanglement ent,
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        String enemyId = ent.getThirdPartyId();
        if (enemyId == null) return results;

        SubfactionPresence enemy = state.presencesMutable().get(enemyId);
        if (enemy == null || !enemy.getState().canLaunchOps()) return results;

        String enemySystem = state.findBaseSystem(enemyId);
        if (enemySystem == null) return results;

        for (String memberId : new String[]{pair.getFirst(), pair.getSecond()}) {
            SubfactionPresence member = state.presencesMutable().get(memberId);
            if (member == null || !member.getState().canLaunchOps()) continue;
            if (state.countActiveOps(memberId, ActiveOp.OpType.JOINT_STRIKE) > 0) continue;

            String memberSystem = state.findBaseSystem(memberId);
            if (memberSystem == null) continue;

            float duration = settings.getOpDuration("JOINT_STRIKE", 25f);
            float chance = settings.getOpSuccessChance("JOINT_STRIKE", 0.55f);
            ActiveOp op = new ActiveOp(state.nextOpId(),
                    ActiveOp.OpType.JOINT_STRIKE, memberId,
                    memberSystem, enemySystem,
                    duration, chance);
            op.setTargetSubfactionId(enemyId);
            state.activeOpsMutable().add(op);
            results.add(new TerritoryState.TickResult.OpLaunched(memberId, op));
        }

        if (!results.isEmpty()) {
            state.entanglementOpCooldownsMutable().put(pair, settings.entanglementOpIntervalDays);
        }
        return results;
    }

    private static List<TerritoryState.TickResult> launchWarRaids(
            SubfactionPair pair, ActiveEntanglement ent,
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        for (String attackerId : new String[]{pair.getFirst(), pair.getSecond()}) {
            String defenderId = pair.other(attackerId);

            SubfactionPresence attacker = state.presencesMutable().get(attackerId);
            SubfactionPresence defender = state.presencesMutable().get(defenderId);
            if (attacker == null || !attacker.getState().canLaunchOps()) continue;
            if (defender == null || !defender.getState().canLaunchOps()) continue;

            if (state.countActiveOps(attackerId, ActiveOp.OpType.WAR_RAID) > 0) continue;

            String attackerSystem = state.findBaseSystem(attackerId);
            if (attackerSystem == null) continue;

            String targetSystem = findActivePatrolSystem(defenderId, state.activeOpsMutable());
            if (targetSystem == null) {
                targetSystem = state.findBaseSystem(defenderId);
            }
            if (targetSystem == null) continue;

            float duration = settings.getOpDuration("WAR_RAID", 30f);
            float chance = settings.getOpSuccessChance("WAR_RAID", 0.60f);
            ActiveOp op = new ActiveOp(state.nextOpId(),
                    ActiveOp.OpType.WAR_RAID, attackerId,
                    attackerSystem, targetSystem,
                    duration, chance);
            op.setTargetSubfactionId(defenderId);
            state.activeOpsMutable().add(op);
            results.add(new TerritoryState.TickResult.OpLaunched(attackerId, op));
        }

        if (!results.isEmpty()) {
            state.entanglementOpCooldownsMutable().put(pair, settings.entanglementOpIntervalDays);
        }
        return results;
    }

    private static List<TerritoryState.TickResult> launchArmsShipment(
            SubfactionPair pair, ActiveEntanglement ent,
            TerritoryState state, IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        String backerId = pair.getFirst();
        String backedId = pair.getSecond();

        SubfactionPresence backer = state.presencesMutable().get(backerId);
        SubfactionPresence backed = state.presencesMutable().get(backedId);
        if (backer == null || !backer.getState().canLaunchOps()) return results;
        if (backed == null || !backed.getState().canLaunchOps()) return results;

        if (state.countActiveOps(backerId, ActiveOp.OpType.ARMS_SHIPMENT) > 0) return results;

        String backerSystem = state.findBaseSystem(backerId);
        String backedSystem = state.findBaseSystem(backedId);
        if (backerSystem == null || backedSystem == null) return results;

        float duration = settings.getOpDuration("ARMS_SHIPMENT", 30f);
        float chance = settings.getOpSuccessChance("ARMS_SHIPMENT", 0.70f);
        ActiveOp op = new ActiveOp(state.nextOpId(),
                ActiveOp.OpType.ARMS_SHIPMENT, backerId,
                backerSystem, backedSystem,
                duration, chance);
        op.setTargetSubfactionId(backedId);
        state.activeOpsMutable().add(op);
        state.entanglementOpCooldownsMutable().put(pair, settings.entanglementOpIntervalDays);
        results.add(new TerritoryState.TickResult.OpLaunched(backerId, op));
        return results;
    }

    /** Find the target system of an active PATROL for the given subfaction, or null. */
    static String findActivePatrolSystem(String subfactionId, List<ActiveOp> activeOps) {
        for (ActiveOp op : activeOps) {
            if (op.getType() == ActiveOp.OpType.PATROL
                    && op.getSubfactionId().equals(subfactionId)
                    && op.isPending()) {
                return op.getTargetSystemId();
            }
        }
        return null;
    }

    // ── Council ──────────────────────────────────────────────────────────

    /**
     * Check if a council should be triggered.
     */
    static List<TerritoryState.TickResult> evaluateCouncilTrigger(
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        if (state.countActiveOps(null, ActiveOp.OpType.COUNCIL) > 0) return results;

        int establishedPlus = 0;
        for (SubfactionPresence p : state.presencesMutable().values()) {
            if (p.getState().canLaunchOps()) establishedPlus++;
        }

        if (establishedPlus < 3) return results;

        String convenerId = null;
        for (SubfactionPresence p : state.presencesMutable().values()) {
            if (p.getState().canLaunchOps()) {
                convenerId = p.getSubfactionId();
                break;
            }
        }
        if (convenerId == null) return results;

        String system = state.findBaseSystem(convenerId);
        if (system == null) system = state.getSystemIds().get(0);

        float duration = settings.getOpDuration("COUNCIL", 20f);
        ActiveOp op = new ActiveOp(state.nextOpId(), ActiveOp.OpType.COUNCIL, convenerId,
                system, system, duration, 1.0f);
        state.activeOpsMutable().add(op);
        state.resetCouncilTimer();
        results.add(new TerritoryState.TickResult.OpLaunched(convenerId, op));
        return results;
    }

    /**
     * Resolve a council op by rolling an outcome and rewriting entanglements.
     */
    static List<TerritoryState.TickResult> resolveCouncil(
            TerritoryState state,
            Map<String, SubfactionDef> subfactionDefs,
            IntrigueSettings settings) {
        Map<SubfactionPair, ActiveEntanglement> entanglements = state.entanglementsMutable();
        Map<String, SubfactionPresence> presences = state.presencesMutable();
        Random opRand = state.opRand();

        CouncilOutcome outcome = rollCouncilOutcome(entanglements, presences, opRand, settings);

        return switch (outcome) {
            case DETENTE -> resolveDetente(entanglements);
            case SELECTIVE_PACTS -> resolveSelectivePacts(state, entanglements, presences, opRand, settings);
            case STATUS_QUO -> List.of();
            case BREAKDOWN -> resolveBreakdown(state, entanglements, presences, subfactionDefs, opRand, settings);
            case CATASTROPHIC_BREAKDOWN -> resolveCatastrophe(state, entanglements, presences, settings);
        };
    }

    private static CouncilOutcome rollCouncilOutcome(
            Map<SubfactionPair, ActiveEntanglement> entanglements,
            Map<String, SubfactionPresence> presences,
            Random opRand, IntrigueSettings settings) {
        int hostileCount = 0;
        int establishedPlus = 0;
        for (ActiveEntanglement ent : entanglements.values()) {
            if (ent.getType().setsHostile) hostileCount++;
        }
        for (SubfactionPresence p : presences.values()) {
            if (p.getState().canLaunchOps()) establishedPlus++;
        }

        int totalPairs = (establishedPlus * (establishedPlus - 1)) / 2;
        float hostileRatio = (totalPairs > 0) ? Math.min(1f, (float) hostileCount / totalPairs) : 0f;

        float pacificationBias = hostileRatio;
        float disruptionBias = 1f - hostileRatio;

        float detenteWeight        = settings.councilDetenteBase + settings.councilDetentePacificationMult * pacificationBias;
        float selectivePactsWeight = settings.councilSelectivePactsBase + settings.councilSelectivePactsPacificationMult * pacificationBias;
        float breakdownWeight      = settings.councilBreakdownBase + settings.councilBreakdownDisruptionMult * disruptionBias;
        float catastropheWeight    = settings.councilCatastropheBase + settings.councilCatastropheDisruptionMult * disruptionBias;
        float statusQuoWeight      = 1f - detenteWeight - selectivePactsWeight
                                       - breakdownWeight - catastropheWeight;

        float roll = opRand.nextFloat();
        if (roll < detenteWeight) return CouncilOutcome.DETENTE;
        if (roll < detenteWeight + selectivePactsWeight) return CouncilOutcome.SELECTIVE_PACTS;
        if (roll < detenteWeight + selectivePactsWeight + statusQuoWeight) return CouncilOutcome.STATUS_QUO;
        if (roll < detenteWeight + selectivePactsWeight + statusQuoWeight + breakdownWeight) return CouncilOutcome.BREAKDOWN;
        return CouncilOutcome.CATASTROPHIC_BREAKDOWN;
    }

    private static List<TerritoryState.TickResult> resolveDetente(
            Map<SubfactionPair, ActiveEntanglement> entanglements) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
        List<SubfactionPair> toRemove = new ArrayList<>();
        for (Map.Entry<SubfactionPair, ActiveEntanglement> e : entanglements.entrySet()) {
            if (e.getValue().getType().setsHostile) {
                toRemove.add(e.getKey());
            }
        }
        for (SubfactionPair pair : toRemove) {
            ActiveEntanglement removed = entanglements.remove(pair);
            if (removed != null) {
                results.add(new TerritoryState.TickResult.EntanglementExpired(removed));
            }
        }
        return results;
    }

    private static List<TerritoryState.TickResult> resolveSelectivePacts(
            TerritoryState state,
            Map<SubfactionPair, ActiveEntanglement> entanglements,
            Map<String, SubfactionPresence> presences,
            Random opRand, IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
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
            SubfactionPresence pF = presences.get(pair.getFirst());
            SubfactionPresence pS = presences.get(pair.getSecond());
            String hirer = null;
            if (pF != null && pS != null) {
                hirer = pF.getState().ordinal() <= pS.getState().ordinal()
                        ? pair.getFirst() : pair.getSecond();
            }
            ActiveEntanglement pact = new ActiveEntanglement(
                    EntanglementType.HIRED_PROTECTION, pair,
                    settings.councilPactDurationDays, null, hirer,
                    "Council negotiation in " + state.getTerritoryId());
            results.add(state.applyEntanglement(pact));
        }
        return results;
    }

    private static List<TerritoryState.TickResult> resolveBreakdown(
            TerritoryState state,
            Map<SubfactionPair, ActiveEntanglement> entanglements,
            Map<String, SubfactionPresence> presences,
            Map<String, SubfactionDef> subfactionDefs,
            Random opRand, IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
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
                if (!state.isHostile(established.get(i), established.get(j), subfactionDefs)) {
                    ActiveEntanglement war = new ActiveEntanglement(
                            EntanglementType.TERRITORIAL_WAR, pair,
                            settings.councilBreakdownWarDurationDays,
                            "Council breakdown in " + state.getTerritoryId());
                    results.add(state.applyEntanglement(war));
                    formed++;
                }
            }
        }
        return results;
    }

    private static List<TerritoryState.TickResult> resolveCatastrophe(
            TerritoryState state,
            Map<SubfactionPair, ActiveEntanglement> entanglements,
            Map<String, SubfactionPresence> presences,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();
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
                        settings.councilCatastropheWarDurationDays,
                        "Catastrophic council breakdown in " + state.getTerritoryId());
                results.add(state.applyEntanglement(war));
            }
        }
        return results;
    }
}
