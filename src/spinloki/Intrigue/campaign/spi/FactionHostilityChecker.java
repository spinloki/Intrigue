package spinloki.Intrigue.campaign.spi;

/**
 * Checks whether two game factions are hostile to each other.
 *
 * In-game: delegates to FactionAPI.isHostileTo().
 * In-sim: configurable (default: all factions are hostile).
 */
public interface FactionHostilityChecker {

    /**
     * @return true if factionIdA and factionIdB are hostile to each other
     */
    boolean areHostile(String factionIdA, String factionIdB);
}

