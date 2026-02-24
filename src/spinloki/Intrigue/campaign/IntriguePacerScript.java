package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

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
        IntriguePeopleManager mgr = IntriguePeopleManager.get();
        List<IntriguePerson> all = new ArrayList<>(mgr.getAll());
        if (all.isEmpty()) return "Pacer tick: no intrigue people.";

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
            mgr.syncMemory(id);
            return verbose
                    ? ("Pacer tick: " + id + " power " + before + " -> " + after)
                    : "";
        } else {
            int before = target.getRelToPlayer();
            int after = clamp(before + delta, -100, 100);
            target.setRelToPlayer(after);
            mgr.syncMemory(id);
            return verbose
                    ? ("Pacer tick: " + id + " relToPlayer " + before + " -> " + after)
                    : "";
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}