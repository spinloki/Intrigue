package spinloki.Intrigue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.thoughtworks.xstream.XStream;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePeopleScript;
import spinloki.Intrigue.config.IntrigueSettings;

import java.util.Map;

public class Intrigue extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        IntrigueSettings.loadSettingsFromJson();
    }

    @Override
    public void configureXStream(XStream x) {
        super.configureXStream(x);
        x.alias("IntriguePeopleManager", IntriguePeopleManager.class);
        x.alias("IntriguePerson", spinloki.Intrigue.campaign.IntriguePerson.class);
        x.alias("IntriguePeopleScript", IntriguePeopleScript.class);
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        // Create and place your initial set of characters
        IntriguePeopleManager.get().bootstrapIfNeeded();
        IntriguePeopleManager.get().refreshAll();
        ensureScript();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Ensure manager exists + script is present on load
        IntriguePeopleManager.get().refreshAll();
        ensureScript();
    }

    private void ensureScript() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_SCRIPT_KEY);

        if (!(existing instanceof IntriguePeopleScript)) {
            IntriguePeopleScript script = new IntriguePeopleScript();
            Global.getSector().addScript(script);
            data.put(IntrigueIds.PERSIST_SCRIPT_KEY, script);
        }
    }
}