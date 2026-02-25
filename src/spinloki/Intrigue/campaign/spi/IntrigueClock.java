package spinloki.Intrigue.campaign.spi;

/**
 * Abstraction over the game clock. The core logic needs two things:
 * - a monotonic timestamp for cooldown tracking
 * - elapsed-days-since for cooldown checks
 *
 * In-game: delegates to Global.getSector().getClock().
 * In-sim: backed by a simple counter.
 */
public interface IntrigueClock {
    /** Return a monotonic timestamp (millis or arbitrary units, must be consistent). */
    long getTimestamp();

    /** Return the number of campaign days elapsed since the given timestamp. */
    float getElapsedDaysSince(long timestamp);
}

