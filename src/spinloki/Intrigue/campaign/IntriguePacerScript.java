package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.OpEvaluator;
import spinloki.Intrigue.campaign.spi.IntrigueOpRunner;
import spinloki.Intrigue.campaign.spi.IntriguePeopleAccess;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class IntriguePacerScript implements EveryFrameScript {
    private final IntervalUtil interval = new IntervalUtil(6.99f, 7.01f);
    private final Random rng = new Random();

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    @Override
    public void advance(float amount) {
        float days = Misc.getDays(amount);
        interval.advance(days);

        if (interval.intervalElapsed()) {
            doOneTick(false);
        }
    }

    /** For console/debug: forces a tick immediately and returns a human-readable result. */
    public String forceTick() {
        return doOneTick(true);
    }

    private String doOneTick(boolean verbose) {
        IntriguePeopleAccess people = IntrigueServices.people();
        List<IntriguePerson> all = new ArrayList<>(people.getAll());
        if (all.isEmpty()) return "Pacer tick: no intrigue people.";

        StringBuilder result = new StringBuilder();

        // ── Op evaluation: each idle person has a chance to start an operation ──
        IntrigueOpRunner opsRunner = IntrigueServices.ops();
        for (IntriguePerson ip : all) {
            if (rng.nextFloat() > 0.20f) continue; // 20% chance per person per tick
            IntrigueOp op = OpEvaluator.evaluate(ip, all, opsRunner, "raid");
            if (op != null) {
                opsRunner.startOp(op);
                if (verbose) {
                    result.append("Op started: ").append(op.getOpTypeName())
                          .append(" by ").append(op.getInitiatorId())
                          .append(" targeting ").append(op.getTargetId()).append("\n");
                }
            }
        }

        // ── Stat nudges (original pacer behavior) ──
        // Prefer people who are "home" (not checked out), but fall back if all are checked out.
        List<IntriguePerson> candidates = new ArrayList<>();
        for (IntriguePerson ip : all) {
            if (!ip.isCheckedOut()) candidates.add(ip);
        }
        if (candidates.isEmpty()) candidates = all;

        IntriguePerson target = candidates.get(rng.nextInt(candidates.size()));

        // Tiny nudge: either power or relToPlayer by -1/0/+1.
        boolean nudgePower = rng.nextBoolean();
        int delta = rng.nextInt(3) - 1; // -1,0,+1
        if (delta == 0) delta = (rng.nextBoolean() ? 1 : -1);

        String id = target.getPersonId();

        if (nudgePower) {
            int before = target.getPower();
            int after = clamp(before + delta, 0, 100);
            target.setPower(after);
            people.syncMemory(id);
            if (verbose) {
                result.append("Pacer tick: ").append(id).append(" power ").append(before).append(" -> ").append(after);
            }
            return result.toString();
        } else {
            int before = target.getRelToPlayer();
            int after = clamp(before + delta, -100, 100);
            target.setRelToPlayer(after);
            people.syncMemory(id);
            if (verbose) {
                result.append("Pacer tick: ").append(id).append(" relToPlayer ").append(before).append(" -> ").append(after);
            }
            return result.toString();
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}