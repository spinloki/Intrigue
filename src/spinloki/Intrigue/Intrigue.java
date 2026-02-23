package spinloki.Intrigue;

import com.fs.starfarer.api.BaseModPlugin;
import spinloki.Intrigue.config.IntrigueSettings;

public class Intrigue extends BaseModPlugin {
    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();

        IntrigueSettings.loadSettingsFromJson();
    }
}
