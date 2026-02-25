package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.spi.IntrigueClock;

/**
 * Sim-side clock implementation. Time is advanced by explicit calls.
 */
public class SimClock implements IntrigueClock {

    private long currentTimestamp = 0;

    @Override
    public long getTimestamp() {
        return currentTimestamp;
    }

    @Override
    public float getElapsedDaysSince(long timestamp) {
        long elapsed = currentTimestamp - timestamp;
        return elapsed / (24f * 60f * 60f * 1000f);
    }

    /** Advance the clock by the given number of days. */
    public void advanceDays(float days) {
        currentTimestamp += (long)(days * 24f * 60f * 60f * 1000f);
    }
}

