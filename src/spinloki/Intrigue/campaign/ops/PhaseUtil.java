package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;

/**
 * Shared utilities for op phases.
 */
public final class PhaseUtil {

    private PhaseUtil() {}

    /**
     * Check whether the game sector is available. Returns {@code false} in sim
     * mode or during early initialization when {@link Global#getSector()} would
     * throw or return null.
     */
    public static boolean isSectorAvailable() {
        try {
            return Global.getSector() != null;
        } catch (Exception e) {
            return false;
        }
    }
}

