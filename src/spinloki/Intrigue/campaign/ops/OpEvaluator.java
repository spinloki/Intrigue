package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.spi.IntrigueOpRunner;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.*;

/**
 * Stateless evaluator that decides whether an IntriguePerson should launch an
 * operation this tick, and if so, what kind and against whom.
 *
 * Scoring is based on the person's traits, power, and relationships.
 * This is intentionally simple now and designed to be extended with more
 * operation types and more sophisticated decision trees later.
 */
public final class OpEvaluator {
    private OpEvaluator() {}

    /** Minimum power required to initiate any operation. */
    public static final int MIN_POWER_THRESHOLD = 35;

    /** Minimum days between operations for the same person. */
    public static final float COOLDOWN_DAYS = 30f;

    /**
     * Evaluate whether this person should start an op right now.
     *
     * @param person     the candidate initiator
     * @param allPeople  all intrigue people (for target selection)
     * @param opsRunner  the ops runner (to check for existing ops)
     * @param opIdPrefix prefix for generating op IDs
     * @return an op to start, or null if the person shouldn't act
     */
    public static IntrigueOp evaluate(IntriguePerson person,
                                       Collection<IntriguePerson> allPeople,
                                       IntrigueOpRunner opsRunner,
                                       String opIdPrefix) {
        if (person == null) return null;

        // Don't act if already checked out or busy with an op
        if (person.isCheckedOut()) return null;
        if (opsRunner.hasActiveOp(person.getPersonId())) return null;

        // Power threshold
        if (person.getPower() < MIN_POWER_THRESHOLD) return null;

        // Cooldown check
        if (isOnCooldown(person)) return null;

        // Find valid targets: other people, different faction, not already under attack
        List<ScoredTarget> targets = scoreTargets(person, allPeople, opsRunner);
        if (targets.isEmpty()) return null;

        // Pick the best-scoring target
        targets.sort((a, b) -> Float.compare(b.score, a.score));
        ScoredTarget best = targets.get(0);

        // Threshold: only act if the score is high enough (avoids random nonsense raids)
        if (best.score < 10f) return null;

        // For now, only RaidOp exists. Future: pick op type based on traits.
        String opId = opsRunner.nextOpId(opIdPrefix);
        return IntrigueServices.opFactory().createRaidOp(opId, person, best.target);
    }

    // ── Scoring ─────────────────────────────────────────────────────────

    private static List<ScoredTarget> scoreTargets(IntriguePerson person,
                                                    Collection<IntriguePerson> allPeople,
                                                    IntrigueOpRunner opsRunner) {
        List<ScoredTarget> results = new ArrayList<>();

        for (IntriguePerson other : allPeople) {
            if (other.getPersonId().equals(person.getPersonId())) continue;

            // Don't target people in the same faction (unless OPPORTUNIST)
            if (other.getFactionId().equals(person.getFactionId())) {
                if (!person.getTraits().contains(IntrigueTraits.OPPORTUNIST)) continue;
            }

            // Don't target people who are already being targeted by an active op
            if (opsRunner.hasActiveOp(other.getPersonId())) continue;

            float score = computeScore(person, other);
            if (score > 0) {
                results.add(new ScoredTarget(other, score));
            }
        }

        return results;
    }

    private static float computeScore(IntriguePerson initiator, IntriguePerson target) {
        float score = 0f;

        // Base: negative relationship → more likely to attack
        Integer rel = initiator.getRelTo(target.getPersonId());
        int relationship = rel != null ? rel : 0;
        score += -relationship * 0.5f; // rel -100 → +50 score; rel +100 → -50 score

        // Power differential: prefer weaker targets
        int powerDiff = initiator.getPower() - target.getPower();
        score += powerDiff * 0.3f;

        // ── Trait modifiers ─────────────────────────────────────────

        Set<String> traits = initiator.getTraits();

        if (traits.contains(IntrigueTraits.MERCILESS)) {
            score += 15f; // MERCILESS people are more aggressive overall
        }

        if (traits.contains(IntrigueTraits.OPPORTUNIST)) {
            // Opportunists love hitting weak targets
            if (powerDiff > 20) score += 10f;
        }

        if (traits.contains(IntrigueTraits.PARANOID)) {
            // Paranoid people strike preemptively against anyone they distrust
            if (relationship < -10) score += 12f;
        }

        if (traits.contains(IntrigueTraits.HONOR_BOUND)) {
            // Honor-bound people are reluctant to attack unless provoked
            score -= 15f;
            if (relationship < -50) score += 20f; // ...unless truly enemies
        }

        if (traits.contains(IntrigueTraits.CHARISMATIC)) {
            // Charismatic people prefer diplomacy (future) over raids
            score -= 10f;
        }

        return score;
    }

    // ── Cooldown ────────────────────────────────────────────────────────

    private static boolean isOnCooldown(IntriguePerson person) {
        long lastOp = person.getLastOpTimestamp();
        if (lastOp <= 0) return false;

        float daysSinceLastOp = IntrigueServices.clock().getElapsedDaysSince(lastOp);
        return daysSinceLastOp < COOLDOWN_DAYS;
    }

    // ── Internal ────────────────────────────────────────────────────────

    private static class ScoredTarget {
        final IntriguePerson target;
        final float score;

        ScoredTarget(IntriguePerson target, float score) {
            this.target = target;
            this.score = score;
        }
    }
}


