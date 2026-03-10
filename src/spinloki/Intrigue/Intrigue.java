package spinloki.Intrigue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;
import spinloki.Intrigue.territory.BaseSlot;
import spinloki.Intrigue.territory.BaseSlotGenerator;
import spinloki.Intrigue.territory.TerritoryConfig;
import spinloki.Intrigue.territory.TerritoryGenerationPlugin;
import spinloki.Intrigue.territory.TerritoryManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Intrigue extends BaseModPlugin {

    private static final Logger log = Logger.getLogger(Intrigue.class);

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        IntrigueSettings.loadSettingsFromJson();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onNewGameAfterProcGen() {
        log.info("Intrigue: onNewGameAfterProcGen — generating territories");
        TerritoryGenerationPlugin.generate();

        log.info("Intrigue: onNewGameAfterProcGen — initializing subfactions");
        SubfactionSetup.initialize();

        log.info("Intrigue: onNewGameAfterProcGen — creating territory managers");
        createTerritoryManagers();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Scripts added via addScript() are auto-restored from save.
        // Just log diagnostic info.
        List<TerritoryManager> managers = TerritoryManager.getManagers();
        log.info("Intrigue: onGameLoad — " + managers.size() + " territory managers loaded from save");
        for (TerritoryManager mgr : managers) {
            log.info("  " + mgr.getTerritoryId() + ": " + mgr.getPresences().size() +
                    " subfactions, " + mgr.getSystemIds().size() + " systems");
        }
    }

    @SuppressWarnings("unchecked")
    private void createTerritoryManagers() {
        // Retrieve generated territory data (territory ID → system IDs)
        Map<String, List<String>> territories = (Map<String, List<String>>)
                Global.getSector().getPersistentData().get(TerritoryGenerationPlugin.PERSISTENT_KEY);
        if (territories == null || territories.isEmpty()) {
            log.warn("Intrigue: No territories found — skipping manager creation");
            return;
        }

        // Retrieve territory defs (stored during generation for base slot config)
        List<TerritoryConfig.TerritoryDef> territoryDefs = (List<TerritoryConfig.TerritoryDef>)
                Global.getSector().getPersistentData().get(TerritoryGenerationPlugin.PERSISTENT_KEY_DEFS);
        if (territoryDefs == null || territoryDefs.isEmpty()) {
            log.warn("Intrigue: No territory defs found — skipping manager creation");
            return;
        }

        // Build lookup: territory ID → TerritoryDef
        Map<String, TerritoryConfig.TerritoryDef> defsByTerritoryId = new LinkedHashMap<>();
        for (TerritoryConfig.TerritoryDef def : territoryDefs) {
            defsByTerritoryId.put(def.id, def);
        }

        // Retrieve subfaction defs
        List<SubfactionDef> subfactionDefs = (List<SubfactionDef>)
                Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
        if (subfactionDefs == null || subfactionDefs.isEmpty()) {
            log.warn("Intrigue: No subfaction defs found — skipping manager creation");
            return;
        }

        List<TerritoryManager> managers = TerritoryManager.createForAllTerritories(
                territories, subfactionDefs);

        for (TerritoryManager manager : managers) {
            TerritoryConfig.TerritoryDef def = defsByTerritoryId.get(manager.getTerritoryId());
            if (def == null) {
                log.warn("Intrigue: No territory def for '" + manager.getTerritoryId() +
                        "' — using default slot generation");
                // Create a minimal def with no pinning and default capacity
                def = new TerritoryConfig.TerritoryDef(
                        manager.getTerritoryId(), manager.getTerritoryId(),
                        0, 0, null, 0, 0,
                        new ArrayList<>(), -1, new ArrayList<>());
            }

            // Generate base slots using config-driven selection
            List<BaseSlot> slots = BaseSlotGenerator.generateSlots(def, manager.getSystemIds());
            manager.setBaseSlots(slots);

            Global.getSector().addScript(manager);
            log.info("  Created TerritoryManager for '" + manager.getTerritoryId() + "' with " +
                    manager.getPresences().size() + " subfactions, " +
                    slots.size() + " base slots");
        }

        // Store in persistent data for easy lookup by console commands etc.
        Global.getSector().getPersistentData().put(TerritoryManager.PERSISTENT_KEY, managers);

        // Clean up the transient territory defs — no longer needed after this point
        Global.getSector().getPersistentData().remove(TerritoryGenerationPlugin.PERSISTENT_KEY_DEFS);

        log.info("Intrigue: Created " + managers.size() + " territory managers");
    }
}
