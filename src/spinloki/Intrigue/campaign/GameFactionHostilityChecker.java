package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import spinloki.Intrigue.campaign.spi.FactionHostilityChecker;

/**
 * Game-side hostility checker that delegates to FactionAPI.isHostileTo().
 */
public class GameFactionHostilityChecker implements FactionHostilityChecker {

    @Override
    public boolean areHostile(String factionIdA, String factionIdB) {
        if (factionIdA == null || factionIdB == null) return false;
        if (factionIdA.equals(factionIdB)) return false;

        FactionAPI factionA = Global.getSector().getFaction(factionIdA);
        if (factionA == null) return false;

        return factionA.isHostileTo(factionIdB);
    }
}

