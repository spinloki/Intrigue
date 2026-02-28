package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueSubfaction.SubfactionType;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueOpRunner;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.*;

/**
 * Stateless evaluator that decides whether a subfaction should launch an
 * operation this tick, and if so, against which target subfaction.
 *
 * Scoring is based on subfaction power, subfaction-to-subfaction relationships,
 * and the leader's personality traits.
 */
public final class OpEvaluator {
    private OpEvaluator() {}

    /** Minimum subfaction home cohesion required to initiate any operation. */
    public static final int MIN_COHESION_THRESHOLD = 35;

    /** Minimum days between operations for the same subfaction. */
    public static final float COOLDOWN_DAYS = 30f;

    /** Legitimacy threshold above which Patrol and Raid are deprioritized. */
    public static final int HIGH_LEGITIMACY_THRESHOLD = 80;
    /** Legitimacy below which patrol is prioritized above routine supplies. */
    public static final int CRITICAL_LEGITIMACY_THRESHOLD = 30;
    /** Legitimacy below which patrol is prioritized above raids (but after supplies). */
    public static final int LOW_LEGITIMACY_THRESHOLD = 50;
    /** Home cohesion below which a Rally op is considered. */
    public static final int RALLY_COHESION_THRESHOLD = 50;

    /** Total cohesion required per extra concurrent op slot (beyond the first). */
    public static final int EXTRA_OP_COHESION_COST = 50;

    /** Success chance penalty applied to an op targeted by mischief (0.15 = 15%). */
    public static final float MISCHIEF_TARGET_SUCCESS_PENALTY = 0.15f;

    /**
     * Weight applied to the target's home cohesion advantage (above 50) when
     * scoring raid targets. High-cohesion subfactions are perceived as threats
     * and attract more aggressive attention from rivals.
     */
    public static final float THREAT_WEIGHT = 0.25f;

    /** Hard cap on concurrent ops per subfaction. */
    public static final int MAX_CONCURRENT_OPS = 3;

    /**
     * Compute a subfaction's total cohesion pool: home cohesion plus
     * cohesion from all ESTABLISHED territories.
     */
    public static int computeTotalCohesion(IntrigueSubfaction subfaction) {
        int total = subfaction.getHomeCohesion();
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            for (IntrigueTerritory t : territories.getAll()) {
                if (t.getPresence(subfaction.getSubfactionId()) == IntrigueTerritory.Presence.ESTABLISHED) {
                    total += t.getCohesion(subfaction.getSubfactionId());
                }
            }
        }
        return total;
    }

    /**
     * How many concurrent ops this subfaction can sustain based on total cohesion.
     * Always at least 1 (the base slot). Each extra slot costs EXTRA_OP_COHESION_COST total cohesion.
     */
    public static int maxConcurrentOps(IntrigueSubfaction subfaction) {
        int totalCohesion = computeTotalCohesion(subfaction);
        int slots = 1 + totalCohesion / EXTRA_OP_COHESION_COST;
        return Math.min(slots, MAX_CONCURRENT_OPS);
    }

    /**
     * Evaluate whether this subfaction should start an op right now.
     *
     * @param subfaction the candidate initiating subfaction
     * @param opsRunner  the ops runner (to check for existing ops)
     * @param opIdPrefix prefix for generating op IDs
     * @return an op to start, or null if the subfaction shouldn't act
     */
    public static IntrigueOp evaluate(IntrigueSubfaction subfaction,
                                       IntrigueOpRunner opsRunner,
                                       String opIdPrefix) {
        if (subfaction == null) return null;

        // Homeless CRIMINAL subfactions can establish a base instead of raiding
        if (!subfaction.hasHomeMarket()) {
            if (subfaction.getType() == SubfactionType.CRIMINAL) {
                return evaluateEstablishBase(subfaction, opsRunner, opIdPrefix);
            }
            // Political subfactions are dormant without a base
            return null;
        }

        // Need a leader to execute the op
        String leaderId = subfaction.getLeaderId();
        if (leaderId == null) return null;

        IntriguePerson leader = IntrigueServices.people().getById(leaderId);
        if (leader == null) return null;

        // Leader must be available
        if (leader.isCheckedOut()) return null;
        int activeOps = opsRunner.getActiveOpCount(leaderId);
        int maxOps = maxConcurrentOps(subfaction);
        if (activeOps >= maxOps) return null;

        // Cooldown on the subfaction
        if (isOnCooldown(subfaction)) return null;

        // ── Priority 0: dysfunction (infighting, expulsion, civil war) ──
        // These fire regardless of home cohesion - they ARE the consequence of low cohesion.
        IntrigueOp dysfunctionOp = evaluateDysfunction(subfaction, opsRunner, opIdPrefix);
        if (dysfunctionOp != null) return dysfunctionOp;

        // ── Priority 0b: CRITICAL patrol - legitimacy < 30, patrol beats everything else ──
        // When legitimacy is this low, restoring it is the single most urgent need
        // (prevents vulnerability raids at 0 legitimacy). Even urgent supplies can wait.
        boolean criticalLegitimacy = subfaction.getLegitimacy() < CRITICAL_LEGITIMACY_THRESHOLD;
        if (criticalLegitimacy) {
            IntrigueOp patrolOp = evaluatePatrol(subfaction, opsRunner, opIdPrefix);
            if (patrolOp != null) return patrolOp;
        }


        // ── Priority 1a: urgent supplies - established territory about to collapse ──
        // Convoys still run even when the subfaction is weak.
        IntrigueOp urgentSupplyOp = evaluateSendSupplies(subfaction, opsRunner, opIdPrefix,
                INFIGHTING_COHESION_THRESHOLD);
        if (urgentSupplyOp != null) return urgentSupplyOp;

        // ── Priority 1b: rally - consolidate home base when cohesion is low ──
        // Fires regardless of MIN_COHESION_THRESHOLD so factions can recover.
        if (subfaction.getHomeCohesion() < RALLY_COHESION_THRESHOLD) {
            String opId = opsRunner.nextOpId(opIdPrefix);
            return IntrigueServices.opFactory().createRallyOp(opId, subfaction);
        }

        // ── Below this point, the subfaction must be strong enough to project power ──
        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD) return null;

        // ── Priority 1b: territory presence building (scout, establish) ──
        IntrigueOp presenceOp = evaluatePresenceOp(subfaction, leader, opsRunner, opIdPrefix);
        if (presenceOp != null) return presenceOp;

        // ── Priority 2b: routine supplies to established territories ──
        IntrigueOp supplyOp = evaluateSendSupplies(subfaction, opsRunner, opIdPrefix, 50);
        if (supplyOp != null) return supplyOp;

        // ── Priority 2c: LOW patrol - legitimacy < 50, patrol before raids ──
        boolean lowLegitimacy = !criticalLegitimacy && subfaction.getLegitimacy() < LOW_LEGITIMACY_THRESHOLD;
        if (lowLegitimacy) {
            IntrigueOp patrolOp = evaluatePatrol(subfaction, opsRunner, opIdPrefix);
            if (patrolOp != null) return patrolOp;
        }

        // Below this point, ops are deprioritized when legitimacy is high
        boolean highLegitimacy = subfaction.getLegitimacy() >= HIGH_LEGITIMACY_THRESHOLD;

        // ── Priority 3: patrol (skip if legitimacy is already high) ──
        if (!highLegitimacy && !criticalLegitimacy && !lowLegitimacy) {
            IntrigueOp patrolOp = evaluatePatrol(subfaction, opsRunner, opIdPrefix);
            if (patrolOp != null) return patrolOp;
        }

        // ── Priority 4: raids (skip if legitimacy is already high) ──
        if (!highLegitimacy) {
            Collection<IntrigueSubfaction> allSubfactions = IntrigueServices.subfactions().getAll();
            List<ScoredTarget> targets = scoreTargets(subfaction, leader, allSubfactions, opsRunner);

            if (!targets.isEmpty()) {
                targets.sort((a, b) -> Float.compare(b.score, a.score));
                ScoredTarget best = targets.get(0);

                if (best.score >= 10f) {
                    String opId = opsRunner.nextOpId(opIdPrefix);
                    return IntrigueServices.opFactory().createRaidOp(opId, subfaction, best.target);
                }
            }
        }

        return null;
    }

    /**
     * Diagnostic: returns a human-readable explanation of why evaluate() would
     * return null for this subfaction, or "READY" with target info if it would act.
     */
    public static String diagnose(IntrigueSubfaction subfaction, IntrigueOpRunner opsRunner) {
        if (subfaction == null) return "null subfaction";

        if (!subfaction.hasHomeMarket()) {
            if (subfaction.getType() == SubfactionType.CRIMINAL) {
                return diagnoseEstablishBase(subfaction, opsRunner);
            }
            return "homeless (dormant) - waiting for a base";
        }

        String leaderId = subfaction.getLeaderId();
        if (leaderId == null) return "no leader";

        IntriguePerson leader = IntrigueServices.people().getById(leaderId);
        if (leader == null) return "leader '" + leaderId + "' not found in people registry";

        if (leader.isCheckedOut()) return "leader checked out";
        int activeOps = opsRunner.getActiveOpCount(leaderId);
        int maxOps = maxConcurrentOps(subfaction);
        if (activeOps >= maxOps) {
            return "at op capacity (" + activeOps + "/" + maxOps
                    + ", totalCoh=" + computeTotalCohesion(subfaction) + ")";
        }
        if (isOnCooldown(subfaction)) return "on cooldown";

        String territoryInfo = diagnoseTerritoryOp(subfaction);

        // Priority 0: dysfunction
        IntrigueOp dysfunctionOp = evaluateDysfunction(subfaction, opsRunner, "diag");
        if (dysfunctionOp != null) {
            return "READY → " + dysfunctionOp.getOpTypeName()
                    + " (territory=" + dysfunctionOp.getTerritoryId() + ")"
                    + "; " + territoryInfo;
        }

        // Priority 1a: urgent supplies
        IntrigueOp urgentSupplyOp = evaluateSendSupplies(subfaction, opsRunner, "diag",
                INFIGHTING_COHESION_THRESHOLD);
        if (urgentSupplyOp != null) {
            return "READY → " + urgentSupplyOp.getOpTypeName()
                    + " (URGENT, territory=" + urgentSupplyOp.getTerritoryId() + ")"
                    + "; " + territoryInfo;
        }

        // Priority 1b: rally
        if (subfaction.getHomeCohesion() < RALLY_COHESION_THRESHOLD) {
            return "READY → Rally (home cohesion " + subfaction.getHomeCohesion()
                    + " < " + RALLY_COHESION_THRESHOLD + "); " + territoryInfo;
        }

        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD) {
            return "home cohesion " + subfaction.getHomeCohesion() + " < " + MIN_COHESION_THRESHOLD
                    + " (only dysfunction/urgent supply ops allowed); " + territoryInfo;
        }

        // Priority 1b: presence building
        IntrigueOp presenceOp = evaluatePresenceOp(subfaction, leader, opsRunner, "diag");
        if (presenceOp != null) {
            return "READY → " + presenceOp.getOpTypeName()
                    + " (territory=" + presenceOp.getTerritoryId() + ")"
                    + "; " + territoryInfo;
        }

        // Priority 2a: critical patrol (legitimacy < 30)
        boolean criticalLegitimacy = subfaction.getLegitimacy() < CRITICAL_LEGITIMACY_THRESHOLD;
        if (criticalLegitimacy) {
            IntrigueOp patrolOp = evaluatePatrol(subfaction, opsRunner, "diag");
            if (patrolOp != null) {
                return "READY → " + patrolOp.getOpTypeName()
                        + " (CRITICAL, legitimacy " + subfaction.getLegitimacy()
                        + " < " + CRITICAL_LEGITIMACY_THRESHOLD
                        + ", territory=" + patrolOp.getTerritoryId() + ")"
                        + "; " + territoryInfo;
            }
        }

        // Priority 2b: routine supplies
        IntrigueOp supplyOp = evaluateSendSupplies(subfaction, opsRunner, "diag", 50);
        if (supplyOp != null) {
            return "READY → " + supplyOp.getOpTypeName()
                    + " (territory=" + supplyOp.getTerritoryId() + ")"
                    + "; " + territoryInfo;
        }

        // Priority 2c: low patrol (legitimacy < 50)
        boolean lowLegitimacy = !criticalLegitimacy && subfaction.getLegitimacy() < LOW_LEGITIMACY_THRESHOLD;
        if (lowLegitimacy) {
            IntrigueOp patrolOp = evaluatePatrol(subfaction, opsRunner, "diag");
            if (patrolOp != null) {
                return "READY → " + patrolOp.getOpTypeName()
                        + " (LOW legitimacy " + subfaction.getLegitimacy()
                        + " < " + LOW_LEGITIMACY_THRESHOLD
                        + ", territory=" + patrolOp.getTerritoryId() + ")"
                        + "; " + territoryInfo;
            }
        }

        boolean highLegitimacy = subfaction.getLegitimacy() >= HIGH_LEGITIMACY_THRESHOLD;

        // Priority 3: patrol
        if (!highLegitimacy && !criticalLegitimacy && !lowLegitimacy) {
            IntrigueOp patrolOp = evaluatePatrol(subfaction, opsRunner, "diag");
            if (patrolOp != null) {
                return "READY → " + patrolOp.getOpTypeName()
                        + " (territory=" + patrolOp.getTerritoryId() + ")"
                        + "; " + territoryInfo;
            }
        }

        // Priority 4: raids
        if (!highLegitimacy) {
            Collection<IntrigueSubfaction> allSubfactions = IntrigueServices.subfactions().getAll();
            List<ScoredTarget> targets = scoreTargets(subfaction, leader, allSubfactions, opsRunner);

            if (!targets.isEmpty()) {
                targets.sort((a, b) -> Float.compare(b.score, a.score));
                ScoredTarget best = targets.get(0);
                if (best.score >= 10f) {
                    return "READY → raid " + best.target.getSubfactionId() + " (score=" + best.score + ")"
                            + "; " + territoryInfo;
                }
                return "best raid score " + best.score + " < 10 (target: "
                        + best.target.getSubfactionId() + "); " + territoryInfo;
            }
        }

        if (highLegitimacy) {
            return "high legitimacy (" + subfaction.getLegitimacy() + " >= " + HIGH_LEGITIMACY_THRESHOLD
                    + "), patrol/raid deprioritized; no supplies needed; " + territoryInfo;
        }

        return "no ops available; " + territoryInfo;
    }
    // ── Scoring ─────────────────────────────────────────────────────────

    private static List<ScoredTarget> scoreTargets(IntrigueSubfaction attacker,
                                                    IntriguePerson leader,
                                                    Collection<IntrigueSubfaction> allSubfactions,
                                                    IntrigueOpRunner opsRunner) {
        List<ScoredTarget> results = new ArrayList<>();

        for (IntrigueSubfaction other : allSubfactions) {
            if (other.getSubfactionId().equals(attacker.getSubfactionId())) continue;

            // Can't raid a subfaction with no market
            if (!other.hasHomeMarket()) continue;

            // Hidden subfactions (e.g. criminal bases) are not valid raid targets
            if (other.isHidden()) continue;

            // Don't target subfactions whose leader is already busy
            String otherLeaderId = other.getLeaderId();
            if (otherLeaderId != null && opsRunner.hasActiveOp(otherLeaderId)) continue;

            // Same-faction subfactions don't raid each other
            if (attacker.getFactionId().equals(other.getFactionId())) continue;

            // Cross-faction raids require parent factions to be hostile
            if (!IntrigueServices.hostility().areHostile(attacker.getFactionId(), other.getFactionId())) {
                continue;
            }

            float score = computeScore(attacker, leader, other);
            if (score > 0) {
                results.add(new ScoredTarget(other, score));
            }
        }

        return results;
    }

    private static float computeScore(IntrigueSubfaction attacker,
                                       IntriguePerson leader,
                                       IntrigueSubfaction target) {
        float score = 0f;

        // Base: negative relationship → more likely to attack
        Integer rel = attacker.getRelTo(target.getSubfactionId());
        int relationship = rel != null ? rel : 0;
        score += -relationship * 0.5f;

        // Low legitimacy targets are more attractive (they look weak/vulnerable)
        score += (50 - target.getLegitimacy()) * 0.3f;

        // High attacker home cohesion gives confidence to act
        score += (attacker.getHomeCohesion() - 50) * 0.2f;

        // Threat: high-cohesion targets look dangerous and should be cut down
        score += Math.max(0, target.getHomeCohesion() - 50) * THREAT_WEIGHT;

        // Cross-faction hostility base
        score += 12f;

        // ── Leader trait modifiers ──────────────────────────────────

        Set<String> traits = leader.getTraits();

        if (traits.contains(IntrigueTraits.MERCILESS)) {
            score += 15f;
        }

        if (traits.contains(IntrigueTraits.OPPORTUNIST)) {
            // Opportunists love hitting low-cohesion targets
            if (attacker.getHomeCohesion() - target.getHomeCohesion() > 15) score += 10f;
        }

        if (traits.contains(IntrigueTraits.PARANOID)) {
            if (relationship < -10) score += 12f;
        }

        if (traits.contains(IntrigueTraits.HONOR_BOUND)) {
            score -= 15f;
            if (relationship < -50) score += 20f;
        }

        if (traits.contains(IntrigueTraits.CHARISMATIC)) {
            score -= 10f;
        }

        return score;
    }

    // ── Establish Base evaluation (for homeless CRIMINAL subfactions) ──

    private static IntrigueOp evaluateEstablishBase(IntrigueSubfaction subfaction,
                                                     IntrigueOpRunner opsRunner,
                                                     String opIdPrefix) {
        String leaderId = subfaction.getLeaderId();
        if (leaderId == null) return null;

        IntriguePerson leader = IntrigueServices.people().getById(leaderId);
        if (leader == null) return null;
        if (leader.isCheckedOut()) return null;
        if (opsRunner.getActiveOpCount(leaderId) >= maxConcurrentOps(subfaction)) return null;

        // Lower home cohesion threshold for establishing a base - desperate factions act sooner
        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD / 2) return null;

        if (isOnCooldown(subfaction)) return null;

        String opId = opsRunner.nextOpId(opIdPrefix);
        return IntrigueServices.opFactory().createEstablishBaseOp(opId, subfaction);
    }

    private static String diagnoseEstablishBase(IntrigueSubfaction subfaction,
                                                 IntrigueOpRunner opsRunner) {
        String leaderId = subfaction.getLeaderId();
        if (leaderId == null) return "CRIMINAL homeless, no leader";

        IntriguePerson leader = IntrigueServices.people().getById(leaderId);
        if (leader == null) return "CRIMINAL homeless, leader not found";
        if (leader.isCheckedOut()) return "CRIMINAL homeless, leader checked out";
        int activeOps = opsRunner.getActiveOpCount(leaderId);
        int maxOps = maxConcurrentOps(subfaction);
        if (activeOps >= maxOps) return "CRIMINAL homeless, at op capacity (" + activeOps + "/" + maxOps + ")";
        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD / 2)
            return "CRIMINAL homeless, home cohesion " + subfaction.getHomeCohesion() + " < " + (MIN_COHESION_THRESHOLD / 2);
        if (isOnCooldown(subfaction)) return "CRIMINAL homeless, on cooldown";

        return "CRIMINAL homeless → READY to establish base";
    }

    // ── Dysfunction evaluation ───────────────────────────────────────────

    /** Thresholds for territory infighting and expulsion. */
    public static final int INFIGHTING_COHESION_THRESHOLD = 30;
    public static final int EXPULSION_COHESION_THRESHOLD = 10;
    public static final int EXPULSION_TICKS_REQUIRED = 3;

    /** Thresholds for home civil war. */
    public static final int CIVIL_WAR_COHESION_THRESHOLD = 10;
    public static final int CIVIL_WAR_TICKS_REQUIRED = 3;

    /**
     * Check for dysfunction consequences that fire when the subfaction is ready.
     * Priority: Civil War > Expulsion > Infighting.
     *
     * Low-cohesion ticks are tracked externally (by the pacer/sim loop).
     * - Infighting fires exactly once: on the first tick cohesion drops below INFIGHTING threshold.
     * - Expulsion fires after cohesion has been below EXPULSION threshold for N ticks.
     * - Civil War fires after home cohesion has been below threshold for N ticks.
     *
     * Returns null if no dysfunction event should fire.
     */
    private static IntrigueOp evaluateDysfunction(IntrigueSubfaction subfaction,
                                                   IntrigueOpRunner opsRunner,
                                                   String opIdPrefix) {
        // Don't stack dysfunction ops on top of each other (intentionally binary, not capacity-based)
        String leaderId = subfaction.getLeaderId();
        if (leaderId != null && opsRunner.hasActiveOp(leaderId)) return null;

        // ── Civil War: home cohesion critically low for too long ──
        if (subfaction.getLowHomeCohesionTicks() >= CIVIL_WAR_TICKS_REQUIRED) {
            String opId = opsRunner.nextOpId(opIdPrefix);
            return IntrigueServices.opFactory().createCivilWarOp(opId, subfaction);
        }

        // ── Territory dysfunction: check each established territory ──
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        String factionId = subfaction.getFactionId();
        String sfId = subfaction.getSubfactionId();

        for (IntrigueTerritory territory : territories.getAll()) {
            if (!territory.isFactionInterested(factionId)) continue;
            if (territory.getPresence(sfId) != IntrigueTerritory.Presence.ESTABLISHED) continue;

            int lowTicks = territory.getLowCohesionTicks(sfId);
            int cohesion = territory.getCohesion(sfId);

            // Expulsion: cohesion critically low AND sustained for too many ticks
            if (cohesion < EXPULSION_COHESION_THRESHOLD && lowTicks >= EXPULSION_TICKS_REQUIRED) {
                String opId = opsRunner.nextOpId(opIdPrefix);
                return IntrigueServices.opFactory().createExpulsionOp(opId, subfaction, territory.getTerritoryId());
            }

            // Infighting: fires exactly once - on the first tick the counter increments
            if (lowTicks == 1) {
                String opId = opsRunner.nextOpId(opIdPrefix);
                return IntrigueServices.opFactory().createInfightingOp(opId, subfaction, territory.getTerritoryId());
            }
        }

        return null;
    }

    // ── Territory Op evaluation ────────────────────────────────────────

    /**
     * Priority 1: Presence building. If any interested territory has NONE or SCOUTING
     * presence, launch a scout or establish op to advance it.
     */
    private static IntrigueOp evaluatePresenceOp(IntrigueSubfaction subfaction,
                                                  IntriguePerson leader,
                                                  IntrigueOpRunner opsRunner,
                                                  String opIdPrefix) {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        String factionId = subfaction.getFactionId();

        for (IntrigueTerritory territory : territories.getAll()) {
            if (!territory.isFactionInterested(factionId)) continue;

            IntrigueTerritory.Presence presence = territory.getPresence(subfaction.getSubfactionId());

            if (presence == IntrigueTerritory.Presence.NONE) {
                String opId = opsRunner.nextOpId(opIdPrefix);
                return IntrigueServices.opFactory().createScoutTerritoryOp(
                        opId, subfaction, territory.getTerritoryId());
            }

            if (presence == IntrigueTerritory.Presence.SCOUTING) {
                String opId = opsRunner.nextOpId(opIdPrefix);
                return IntrigueServices.opFactory().createEstablishTerritoryBaseOp(
                        opId, subfaction, territory.getTerritoryId());
            }
        }

        return null;
    }

    /**
     * Send supplies to the established territory with the lowest cohesion,
     * if any territory cohesion is below the given threshold.
     */
    private static IntrigueOp evaluateSendSupplies(IntrigueSubfaction subfaction,
                                                    IntrigueOpRunner opsRunner,
                                                    String opIdPrefix,
                                                    int threshold) {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        String factionId = subfaction.getFactionId();
        String sfId = subfaction.getSubfactionId();

        IntrigueTerritory neediest = null;
        int lowestCohesion = Integer.MAX_VALUE;

        for (IntrigueTerritory territory : territories.getAll()) {
            if (!territory.isFactionInterested(factionId)) continue;
            if (territory.getPresence(sfId) != IntrigueTerritory.Presence.ESTABLISHED) continue;

            int coh = territory.getCohesion(sfId);
            if (coh < threshold && coh < lowestCohesion) {
                lowestCohesion = coh;
                neediest = territory;
            }
        }

        if (neediest != null) {
            String opId = opsRunner.nextOpId(opIdPrefix);
            return IntrigueServices.opFactory().createSendSuppliesOp(
                    opId, subfaction, neediest.getTerritoryId());
        }

        return null;
    }

    /**
     * Priority 3: Patrol an established territory. Picks the first
     * established territory found.
     */
    private static IntrigueOp evaluatePatrol(IntrigueSubfaction subfaction,
                                              IntrigueOpRunner opsRunner,
                                              String opIdPrefix) {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        String factionId = subfaction.getFactionId();

        for (IntrigueTerritory territory : territories.getAll()) {
            if (!territory.isFactionInterested(factionId)) continue;
            if (territory.getPresence(subfaction.getSubfactionId()) == IntrigueTerritory.Presence.ESTABLISHED) {
                String opId = opsRunner.nextOpId(opIdPrefix);
                return IntrigueServices.opFactory().createPatrolOp(
                        opId, subfaction, territory.getTerritoryId());
            }
        }

        return null;
    }

    private static String diagnoseTerritoryOp(IntrigueSubfaction subfaction) {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return "territories not available";

        String factionId = subfaction.getFactionId();
        StringBuilder sb = new StringBuilder();
        boolean foundAny = false;

        for (IntrigueTerritory territory : territories.getAll()) {
            if (!territory.isFactionInterested(factionId)) continue;
            foundAny = true;

            IntrigueTerritory.Presence presence = territory.getPresence(subfaction.getSubfactionId());
            sb.append(territory.getName()).append("=").append(presence).append(" ");
        }

        if (!foundAny) return "no interested territories for faction " + factionId;
        return "territory status: " + sb.toString().trim();
    }

    // ── Vulnerability Raids ─────────────────────────────────────────────
    /**
     * Evaluate vulnerability raids: when a subfaction's legitimacy drops to 0,
     * every hostile subfaction sends a free raid against it. These ops:
     *   - bypass cooldown and active-op checks
     *   - don't set cooldown on the attacker
     *   - don't cost the attacker anything on failure
     *   - are in addition to the attacker's normal op for this tick
     *
     * @return list of free raid ops to start (may be empty, never null)
     */
    public static List<IntrigueOp> evaluateVulnerabilityRaids(
            IntrigueOpRunner opsRunner, String opIdPrefix) {
        List<IntrigueOp> raids = new ArrayList<>();
        Collection<IntrigueSubfaction> allSubfactions = IntrigueServices.subfactions().getAll();
        for (IntrigueSubfaction victim : allSubfactions) {
            if (victim.getLegitimacy() > 0) continue;
            if (!victim.hasHomeMarket()) continue;
            for (IntrigueSubfaction attacker : allSubfactions) {
                if (attacker == victim) continue;
                if (attacker.getSubfactionId().equals(victim.getSubfactionId())) continue;
                if (!attacker.hasHomeMarket()) continue;
                // Must be from a different, hostile faction
                if (attacker.getFactionId().equals(victim.getFactionId())) continue;
                if (!IntrigueServices.hostility().areHostile(
                        attacker.getFactionId(), victim.getFactionId())) continue;
                // Must have a leader (but don't check if leader is busy - this is free)
                if (attacker.getLeaderId() == null) continue;
                String opId = opsRunner.nextOpId(opIdPrefix + "_vuln");
                IntrigueOp raid = IntrigueServices.opFactory().createRaidOp(opId, attacker, victim);
                if (raid != null) {
                    raid.setNoCost(true);
                    raids.add(raid);
                }
            }
        }
        return raids;
    }

    /**
     * Evaluate friction-triggered ops for subfactions sharing a territory.
     *
     * <p>For each territory, for each pair of ESTABLISHED subfactions where
     * friction exceeds the threshold:</p>
     * <ul>
     *   <li><b>Hostile factions:</b> the initiator launches a free raid against the victim
     *       (escalation - no need for petty sabotage when you're at war).</li>
     *   <li><b>Non-hostile factions:</b> the initiator launches a mischief op targeting
     *       a random active op of the victim. If the victim has no active op to sabotage,
     *       friction stays (not reset) and is re-checked next tick.</li>
     * </ul>
     *
     * <p>The subfaction with higher legitimacy initiates (tiebreak: alphabetical).</p>
     *
     * @param opsRunner   the op runner (for generating IDs and querying active ops)
     * @param opIdPrefix  prefix for generated op IDs
     * @param frictionThreshold friction level that triggers action
     * @return list of ops to start (all have noCost=true)
     */
    public static List<IntrigueOp> evaluateMischiefOps(
            IntrigueOpRunner opsRunner, String opIdPrefix, int frictionThreshold) {
        List<IntrigueOp> ops = new ArrayList<>();
        if (IntrigueServices.territories() == null) return ops;

        List<IntrigueOp> activeOps = opsRunner.getActiveOps();

        for (IntrigueTerritory territory : IntrigueServices.territories().getAll()) {
            for (String[] pair : territory.getEstablishedPairs()) {
                String sfA = pair[0];
                String sfB = pair[1];

                // Check both directions - each can trigger independently
                int frictionAB = territory.getFriction(sfA, sfB);
                int frictionBA = territory.getFriction(sfB, sfA);

                // Process whichever direction(s) crossed the threshold
                // (both could fire in the same tick if both are above threshold)
                for (int dir = 0; dir < 2; dir++) {
                    String initId = (dir == 0) ? sfA : sfB;
                    String victId = (dir == 0) ? sfB : sfA;
                    int friction = (dir == 0) ? frictionAB : frictionBA;
                    if (friction < frictionThreshold) continue;

                    IntrigueSubfaction initiator = IntrigueServices.subfactions().getById(initId);
                    IntrigueSubfaction victim = IntrigueServices.subfactions().getById(victId);
                    if (initiator == null || victim == null) continue;
                    if (initiator.getLeaderId() == null) continue;

                    boolean hostile = IntrigueServices.hostility().areHostile(
                            initiator.getFactionId(), victim.getFactionId());

                    if (hostile) {
                        // Hostile factions skip mischief - launch a free raid targeting
                        // the victim's base in this shared territory (the source of friction).
                        String territoryBaseId = territory.getBaseMarketId(victim.getSubfactionId());
                        boolean hasTarget = (territoryBaseId != null && !territoryBaseId.isEmpty())
                                || victim.hasHomeMarket();
                        if (!hasTarget) continue;

                        String opId = opsRunner.nextOpId(opIdPrefix + "_friction_raid");
                        IntrigueOp raid = IntrigueServices.opFactory().createRaidOp(
                                opId, initiator, victim, territoryBaseId);
                        if (raid != null) {
                            raid.setNoCost(true);
                            raid.setTerritoryId(territory.getTerritoryId());
                            ops.add(raid);
                        }
                        // Reset only the initiator's directed friction
                        territory.resetFriction(initId, victId);
                    } else {
                        // Non-hostile: mischief, but only if the victim has a targetable active op
                        List<IntrigueOp> victimOps = new ArrayList<>();
                        for (IntrigueOp op : activeOps) {
                            if (op.isResolved()) continue;
                            String opSfId = op.getInitiatorSubfactionId();
                            if (opSfId != null && opSfId.equals(victim.getSubfactionId())
                                    && op.canBeTargetedByMischief()) {
                                victimOps.add(op);
                            }
                        }
                        if (victimOps.isEmpty()) {
                            // No targetable op - friction stays, re-check next tick
                            continue;
                        }

                        IntrigueOp targetOp = victimOps.get((int) (Math.random() * victimOps.size()));
                        String opId = opsRunner.nextOpId(opIdPrefix + "_mischief");
                        IntrigueOp mischiefOp = IntrigueServices.opFactory().createMischiefOp(
                                opId, initiator, victim, territory.getTerritoryId(), targetOp);
                        if (mischiefOp != null) {
                            mischiefOp.setNoCost(true);
                            ops.add(mischiefOp);
                            // Apply the generic success penalty AND the op-specific sabotage
                            targetOp.addMischiefPenalty(MISCHIEF_TARGET_SUCCESS_PENALTY);
                            targetOp.applyMischiefSabotage();
                        }
                        // Reset only the initiator's directed friction
                        territory.resetFriction(initId, victId);
                    }
                }
            }
        }
        return ops;
    }

    // ── Cooldown ────────────────────────────────────────────────────────
    private static boolean isOnCooldown(IntrigueSubfaction subfaction) {
        long lastOp = subfaction.getLastOpTimestamp();
        if (lastOp <= 0) return false;

        float daysSinceLastOp = IntrigueServices.clock().getElapsedDaysSince(lastOp);
        return daysSinceLastOp < COOLDOWN_DAYS;
    }

    // ── Internal ────────────────────────────────────────────────────────

    private static class ScoredTarget {
        final IntrigueSubfaction target;
        final float score;

        ScoredTarget(IntrigueSubfaction target, float score) {
            this.target = target;
            this.score = score;
        }
    }
}