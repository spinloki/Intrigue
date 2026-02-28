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

        // Subfaction home cohesion threshold
        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD) return null;

        // Cooldown on the subfaction
        if (isOnCooldown(subfaction)) return null;

        // ── Priority 1: territory ops (scouting, establishing bases) ──
        IntrigueOp territoryOp = evaluateTerritoryOp(subfaction, leader, opsRunner, opIdPrefix);
        if (territoryOp != null) return territoryOp;

        // ── Priority 2: raids (last resort when nothing else to do) ──
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
            return "homeless (dormant) — waiting for a base";
        }

        String leaderId = subfaction.getLeaderId();
        if (leaderId == null) return "no leader";

        IntriguePerson leader = IntrigueServices.people().getById(leaderId);
        if (leader == null) return "leader '" + leaderId + "' not found in people registry";

        if (leader.isCheckedOut()) return "leader checked out";
        if (opsRunner.hasActiveOp(leaderId)) return "leader has active op";

        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD)
            return "home cohesion " + subfaction.getHomeCohesion() + " < " + MIN_COHESION_THRESHOLD;

        if (isOnCooldown(subfaction)) return "on cooldown";

        String territoryInfo = diagnoseTerritoryOp(subfaction);

        // Territory ops checked first (higher priority)
        IntrigueOp territoryOp = evaluateTerritoryOp(subfaction, leader, opsRunner, "diag");
        if (territoryOp != null) {
            return "READY → " + territoryOp.getOpTypeName()
                    + " (territory=" + territoryOp.getTerritoryId() + ")"
                    + "; " + territoryInfo;
        }

        // Raids checked second (lower priority)
        Collection<IntrigueSubfaction> allSubfactions = IntrigueServices.subfactions().getAll();
        List<ScoredTarget> targets = scoreTargets(subfaction, leader, allSubfactions, opsRunner);

        if (targets.isEmpty()) return "no territory ops, no valid raid targets; " + territoryInfo;

        targets.sort((a, b) -> Float.compare(b.score, a.score));
        ScoredTarget best = targets.get(0);

        if (best.score < 10f) {
            return "no territory ops; best raid score " + best.score + " < 10 (target: "
                    + best.target.getSubfactionId() + "); " + territoryInfo;
        }

        return "READY → raid " + best.target.getSubfactionId() + " (score=" + best.score + ")"
                + "; " + territoryInfo;
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
        if (opsRunner.hasActiveOp(leaderId)) return null;

        // Lower home cohesion threshold for establishing a base — desperate factions act sooner
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
        if (opsRunner.hasActiveOp(leaderId)) return "CRIMINAL homeless, leader has active op";
        if (subfaction.getHomeCohesion() < MIN_COHESION_THRESHOLD / 2)
            return "CRIMINAL homeless, home cohesion " + subfaction.getHomeCohesion() + " < " + (MIN_COHESION_THRESHOLD / 2);
        if (isOnCooldown(subfaction)) return "CRIMINAL homeless, on cooldown";

        return "CRIMINAL homeless → READY to establish base";
    }

    // ── Territory Op evaluation ────────────────────────────────────────

    /**
     * Evaluate whether this subfaction should pursue a territory op.
     * Checks all territories where the subfaction's parent faction is interested:
     *   - If presence is NONE → create a ScoutTerritoryOp
     *   - If presence is SCOUTING → create an EstablishTerritoryBaseOp
     *   - If presence is ESTABLISHED → no action (future ops will go here)
     */
    private static IntrigueOp evaluateTerritoryOp(IntrigueSubfaction subfaction,
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

            if (presence == IntrigueTerritory.Presence.ESTABLISHED) {
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