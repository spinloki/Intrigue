package spinloki.Intrigue.campaign.spi;

import com.fs.starfarer.api.Global;

/**
 * IntrigueClock backed by the real Starsector campaign clock.
 */
public class SectorClock implements IntrigueClock {

    @Override
    public long getTimestamp() {
        return Global.getSector().getClock().getTimestamp();
    }

    @Override
    public float getElapsedDaysSince(long timestamp) {
        return Global.getSector().getClock().getElapsedDaysSince(timestamp);
    }
}

