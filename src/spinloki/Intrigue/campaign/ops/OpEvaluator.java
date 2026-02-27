package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueSubfaction.SubfactionType;
import spinloki.Intrigue.campaign.spi.IntrigueOpRunner;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

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

    /** Minimum subfaction power required to initiate any operation. */
    public static final int MIN_POWER_THRESHOLD = 35;

    /** Minimum days between operations for the same subfaction. */
    public static final float COOLDOWN_DAYS = 30f;

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
        if (opsRunner.hasActiveOp(leaderId)) return null;

        // Subfaction power threshold
        if (subfaction.getPower() < MIN_POWER_THRESHOLD) return null;

        // Cooldown on the subfaction
        if (isOnCooldown(subfaction)) return null;

        // Find valid target subfactions
        Collection<IntrigueSubfaction> allSubfactions = IntrigueServices.subfactions().getAll();
        List<ScoredTarget> targets = scoreTargets(subfaction, leader, allSubfactions, opsRunner);
        if (targets.isEmpty()) return null;

        // Pick the best-scoring target
        targets.sort((a, b) -> Float.compare(b.score, a.score));
        ScoredTarget best = targets.get(0);

        // Threshold: only act if the score is high enough
        if (best.score < 10f) return null;

        String opId = opsRunner.nextOpId(opIdPrefix);
        return IntrigueServices.opFactory().createRaidOp(opId, subfaction, best.target);
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
            return "homeless (dormant) — waiting for a base";
        }

        String leaderId = subfaction.getLeaderId();
        if (leaderId == null) return "no leader";

        IntriguePerson leader = IntrigueServices.people().getById(leaderId);
        if (leader == null) return "leader '" + leaderId + "' not found in people registry";

        if (leader.isCheckedOut()) return "leader checked out";
        if (opsRunner.hasActiveOp(leaderId)) return "leader has active op";

        if (subfaction.getPower() < MIN_POWER_THRESHOLD)
            return "power " + subfaction.getPower() + " < " + MIN_POWER_THRESHOLD;

        if (isOnCooldown(subfaction)) return "on cooldown";

        Collection<IntrigueSubfaction> allSubfactions = IntrigueServices.subfactions().getAll();
        List<ScoredTarget> targets = scoreTargets(subfaction, leader, allSubfactions, opsRunner);
        if (targets.isEmpty()) return "no valid targets (hostility/availability)";

        targets.sort((a, b) -> Float.compare(b.score, a.score));
        ScoredTarget best = targets.get(0);

        if (best.score < 10f) return "best score " + best.score + " < 10 (target: " + best.target.getSubfactionId() + ")";

        return "READY → " + best.target.getSubfactionId() + " (score=" + best.score + ")";
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

        // Power differential: prefer weaker targets
        int powerDiff = attacker.getPower() - target.getPower();
        score += powerDiff * 0.3f;

        // Cross-faction hostility base
        score += 12f;

        // ── Leader trait modifiers ──────────────────────────────────

        Set<String> traits = leader.getTraits();

        if (traits.contains(IntrigueTraits.MERCILESS)) {
            score += 15f;
        }

        if (traits.contains(IntrigueTraits.OPPORTUNIST)) {
            // Opportunists love hitting weaker subfactions
            if (powerDiff > 15) score += 10f;
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
        if (opsRunner.hasActiveOp(leaderId)) return null;

        // Lower power threshold for establishing a base — desperate factions act sooner
        if (subfaction.getPower() < MIN_POWER_THRESHOLD / 2) return null;

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
        if (opsRunner.hasActiveOp(leaderId)) return "CRIMINAL homeless, leader has active op";
        if (subfaction.getPower() < MIN_POWER_THRESHOLD / 2)
            return "CRIMINAL homeless, power " + subfaction.getPower() + " < " + (MIN_POWER_THRESHOLD / 2);
        if (isOnCooldown(subfaction)) return "CRIMINAL homeless, on cooldown";

        return "CRIMINAL homeless → READY to establish base";
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