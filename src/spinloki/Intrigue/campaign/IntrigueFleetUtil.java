package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;

/**
 * Shared utility methods for configuring Intrigue fleets.
 */
public final class IntrigueFleetUtil {

    private IntrigueFleetUtil() {}

    /**
     * Tag a fleet as belonging to the Intrigue mod and a specific subfaction.
     * Sets the {@code $intrigueFleet} and {@code $intrigueSubfaction} memory keys.
     *
     * @param fleet           the fleet to tag
     * @param subfactionName  display name of the owning subfaction
     */
    public static void tagIntrigueFleet(CampaignFleetAPI fleet, String subfactionName) {
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionName);
    }

    /**
     * Configure a fleet so it won't get sidetracked by hostiles.
     * Sets {@link MemFlags#MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED} and
     * {@link MemFlags#MEMORY_KEY_MAKE_NON_HOSTILE} so the fleet ignores
     * other fleets and other fleets ignore it.
     *
     * @param fleet the fleet to configure
     */
    public static void makeFocused(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
    }

    /**
     * Remove the focused flags, re-enabling normal hostile AI behavior.
     *
     * @param fleet the fleet to unfocus
     */
    public static void removeFocused(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED);
        fleet.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
    }
}

