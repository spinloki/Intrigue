package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

// This script is strictly for synchronization, not actual game logic.
// It calls refreshAll on the concrete manager, not through the SPI,
// because refreshAll is a game-side concern (market placement, memory sync).
public class IntriguePeopleScript implements EveryFrameScript {

    private transient IntervalUtil interval = new IntervalUtil(1f, 1.5f);

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
        if (interval == null) interval = new IntervalUtil(1f, 1.5f);

        float days = Misc.getDays(amount);
        interval.advance(days);

        if (interval.intervalElapsed()) {
            IntriguePeopleManager.get().refreshAll();
        }
    }
}