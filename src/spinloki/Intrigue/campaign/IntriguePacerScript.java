package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.OpEvaluator;
import spinloki.Intrigue.campaign.spi.IntrigueOpRunner;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueSubfactionAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class IntriguePacerScript implements EveryFrameScript {
    private final IntervalUtil interval = new IntervalUtil(6.99f, 7.01f);
    private final Random rng = new Random();

    @Override
    public boolean isDone() { return false; }

    @Override
    public boolean runWhilePaused() { return false; }

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
        IntrigueSubfactionAccess subfactions = IntrigueServices.subfactions();
        List<IntrigueSubfaction> allSubfactions = new ArrayList<>(subfactions.getAll());
        if (allSubfactions.isEmpty()) return "Pacer tick: no subfactions.";

        StringBuilder result = new StringBuilder();

        // ── Op evaluation: each subfaction has a chance to start an operation ──
        IntrigueOpRunner opsRunner = IntrigueServices.ops();
        for (IntrigueSubfaction sf : allSubfactions) {
            if (rng.nextFloat() > 0.20f) continue; // 20% chance per subfaction per tick
            IntrigueOp op = OpEvaluator.evaluate(sf, opsRunner, "raid");
            if (op != null) {
                opsRunner.startOp(op);
                if (verbose) {
                    result.append("Op started: ").append(op.getOpTypeName())
                          .append(" by ").append(sf.getSubfactionId())
                          .append(" (leader ").append(op.getInitiatorId()).append(")")
                          .append(" targeting ").append(op.getTargetSubfactionId()).append("\n");
                }
            }
        }

        // ── Power nudges: nudge a random subfaction's power ──
        IntrigueSubfaction target = allSubfactions.get(rng.nextInt(allSubfactions.size()));

        int delta = rng.nextInt(3) - 1; // -1,0,+1
        if (delta == 0) delta = (rng.nextBoolean() ? 1 : -1);

        int before = target.getPower();
        int after = clamp(before + delta, 0, 100);
        target.setPower(after);
        if (verbose) {
            result.append("Pacer tick: ").append(target.getSubfactionId())
                  .append(" power ").append(before).append(" -> ").append(after);
        }

        return result.toString();
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}