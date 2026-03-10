package spinloki.Intrigue;

import com.fs.starfarer.api.BaseModPlugin;
import org.apache.log4j.Logger;
import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.territory.TerritoryGenerationPlugin;

public class Intrigue extends BaseModPlugin {

    private static final Logger log = Logger.getLogger(Intrigue.class);

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        IntrigueSettings.loadSettingsFromJson();
    }

    @Override
    public void onNewGameAfterProcGen() {
        log.info("Intrigue: onNewGameAfterProcGen — generating territories");
        TerritoryGenerationPlugin.generate();
    }
}
