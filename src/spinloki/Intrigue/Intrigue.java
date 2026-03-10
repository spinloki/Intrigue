package spinloki.Intrigue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;
import spinloki.Intrigue.territory.TerritoryGenerationPlugin;
import spinloki.Intrigue.territory.TerritoryManager;

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
            Global.getSector().addScript(manager);
            log.info("  Created TerritoryManager for '" + manager.getTerritoryId() + "' with " +
                    manager.getPresences().size() + " subfactions");
        }

        // Store in persistent data for easy lookup by console commands etc.
        Global.getSector().getPersistentData().put(TerritoryManager.PERSISTENT_KEY, managers);

        log.info("Intrigue: Created " + managers.size() + " territory managers");
    }
}
