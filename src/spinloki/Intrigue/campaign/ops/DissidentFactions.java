package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages per-parent-faction "dissident" dummy factions used by infighting ops.
 *
 * <p>When two fleets from the same faction need to fight each other, one fleet
 * is reassigned to a dissident faction that shares the parent's UI colors but
 * is permanently hostile to the parent. This avoids polluting real faction
 * relationships and gives the dissident fleet a visually coherent appearance.</p>
 *
 * <p>Dissident factions are defined as static .faction files under
 * data/world/factions/ and loaded automatically by the game.</p>
 */
public final class DissidentFactions {

    private static final Logger log = Logger.getLogger(DissidentFactions.class.getName());
    private static final String ID_PREFIX = "intrigue_dissidents_";

    /** Track which dissident factions have had their relationship initialized this session. */
    private static final Set<String> initialized = new HashSet<>();

    private DissidentFactions() {}

    /**
     * Clear the per-session initialization state. Must be called on each game
     * load so that dissident-faction hostility is re-established for the new
     * save instead of carrying over stale state from a previous session.
     */
    public static void reset() {
        initialized.clear();
    }

    /**
     * Get the dissident faction ID for a given parent faction.
     */
    public static String getDissidentFactionId(String parentFactionId) {
        return ID_PREFIX + parentFactionId;
    }

    /**
     * Get the dissident faction for the given parent faction.
     * On first access, ensures the hostile relationship is set via the Java API
     * (the .faction file relationships field is not loaded by the game).
     * Returns null if no dissident faction is defined for this parent.
     *
     * @param parentFactionId the real faction ID (e.g. "hegemony")
     * @return the dissident FactionAPI, or null if not found
     */
    public static FactionAPI get(String parentFactionId) {
        if (parentFactionId == null) return null;

        String dissidentId = getDissidentFactionId(parentFactionId);
        FactionAPI faction = Global.getSector().getFaction(dissidentId);
        if (faction == null) {
            log.warning("DissidentFactions: no dissident faction found for " + parentFactionId
                    + " (expected " + dissidentId + ")");
            return null;
        }

        // Ensure the hostile relationship is set (only once per session)
        if (initialized.add(dissidentId)) {
            faction.setRelationship(parentFactionId, RepLevel.VENGEFUL);
            log.info("DissidentFactions: set " + dissidentId + " VENGEFUL toward " + parentFactionId);
        }

        return faction;
    }
}
