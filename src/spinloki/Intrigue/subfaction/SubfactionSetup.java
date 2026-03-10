package spinloki.Intrigue.subfaction;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import org.apache.log4j.Logger;
import org.json.JSONException;

import java.io.IOException;
import java.util.List;

/**
 * Initializes subfaction relationships at game start.
 *
 * The faction identity (colors, doctrine, ships) is loaded automatically by
 * the engine from the {@code .faction} files registered in {@code factions.csv}.
 * This class handles the one thing that can't be done declaratively:
 * copying the parent faction's relationships so the subfaction has the same
 * diplomatic stance as its parent.
 *
 * Call {@link #initialize()} from {@code onNewGameAfterProcGen()}.
 */
public class SubfactionSetup {

    private static final Logger log = Global.getLogger(SubfactionSetup.class);

    /** Persistent data key for the loaded subfaction def list. */
    public static final String PERSISTENT_KEY = "intrigue_subfaction_defs";

    /**
     * Load subfaction configs and copy parent faction relationships.
     */
    public static void initialize() {
        log.info("=== Intrigue: Initializing subfactions ===");

        List<SubfactionDef> defs;
        try {
            defs = SubfactionConfig.load();
        } catch (IOException | JSONException e) {
            log.error("Failed to load subfaction config!", e);
            return;
        }

        for (SubfactionDef def : defs) {
            initializeSubfaction(def);
        }

        // Store defs for later milestones (TerritoryManager will need to look these up)
        Global.getSector().getPersistentData().put(PERSISTENT_KEY, defs);

        log.info("=== Intrigue: Subfaction initialization complete. " +
                defs.size() + " subfactions configured. ===");
    }

    /**
     * Copy the parent faction's relationships to the subfaction.
     * The subfaction starts with the same diplomatic stance as its parent
     * toward every other faction in the sector.
     */
    private static void initializeSubfaction(SubfactionDef def) {
        FactionAPI subfaction = Global.getSector().getFaction(def.id);
        if (subfaction == null) {
            log.error("  Subfaction '" + def.id + "' not found! " +
                    "Is it registered in factions.csv and does the .faction file exist?");
            return;
        }

        FactionAPI parent = Global.getSector().getFaction(def.parentFactionId);
        if (parent == null) {
            log.error("  Parent faction '" + def.parentFactionId + "' not found for subfaction '" +
                    def.id + "'!");
            return;
        }

        // Copy relationships from parent to subfaction
        int copied = 0;
        for (FactionAPI other : Global.getSector().getAllFactions()) {
            if (other == subfaction) continue;

            // Get the parent's relationship with this faction
            float parentRel = parent.getRelationship(other.getId());
            subfaction.setRelationship(other.getId(), parentRel);
            copied++;
        }

        // Subfaction should be friendly with its parent
        subfaction.setRelationship(def.parentFactionId, 1f);

        log.info("  Initialized '" + def.name + "' [" + def.id + "]: " +
                "copied " + copied + " relationships from parent '" + def.parentFactionId + "', " +
                "doctrine: w=" + subfaction.getDoctrine().getWarships() +
                " c=" + subfaction.getDoctrine().getCarriers() +
                " p=" + subfaction.getDoctrine().getPhaseShips() +
                " size=" + subfaction.getDoctrine().getShipSize() +
                " aggro=" + subfaction.getDoctrine().getAggression());
    }
}

