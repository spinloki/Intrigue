package spinloki.Intrigue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.thoughtworks.xstream.XStream;
import spinloki.Intrigue.campaign.GameFactionHostilityChecker;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePeopleScript;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueSubfactionManager;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.IntrigueTerritoryManager;
import spinloki.Intrigue.campaign.TerritoryPatrolScript;
import spinloki.Intrigue.campaign.intel.IntrigueTerritoryIntel;
import spinloki.Intrigue.campaign.ops.*;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.SectorClock;
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
        x.alias("IntrigueOpsManager", IntrigueOpsManager.class);
        x.alias("IntrigueOp", IntrigueOp.class);
        x.alias("RaidOp", RaidOp.class);
        x.alias("AssemblePhase", AssemblePhase.class);
        x.alias("TravelAndFightPhase", TravelAndFightPhase.class);
        x.alias("ReturnPhase", ReturnPhase.class);
        x.alias("FGIPhase", FGIPhase.class);
        x.alias("IntrigueRaidIntel", IntrigueRaidIntel.class);
        x.alias("IntrigueOpIntel", IntrigueOpIntel.class);
        x.alias("IntrigueTerritoryIntel", IntrigueTerritoryIntel.class);
        x.alias("EstablishBaseOp", EstablishBaseOp.class);
        x.alias("EstablishBasePhase", EstablishBasePhase.class);
        x.alias("ScoutTerritoryOp", ScoutTerritoryOp.class);
        x.alias("ScoutTerritoryPhase", ScoutTerritoryPhase.class);
        x.alias("EstablishTerritoryBaseOp", EstablishTerritoryBaseOp.class);
        x.alias("AssaultTerritoryBaseOp", AssaultTerritoryBaseOp.class);
        x.alias("UpgradePresenceOp", UpgradePresenceOp.class);
        x.alias("SlotSystemPicker", SlotSystemPicker.class);
        x.alias("BaseSlot", IntrigueTerritory.BaseSlot.class);
        x.alias("PatrolOp", PatrolOp.class);
        x.alias("PatrolPhase", PatrolPhase.class);
        x.alias("SendSuppliesOp", SendSuppliesOp.class);
        x.alias("SendSuppliesPhase", SendSuppliesPhase.class);
        x.alias("InfightingOp", InfightingOp.class);
        x.alias("InfightingPhase", InfightingPhase.class);
        x.alias("FactionBattlePhase", FactionBattlePhase.class);
        x.alias("TimedPhase", TimedPhase.class);
        x.alias("ExpulsionOp", ExpulsionOp.class);
        x.alias("CivilWarOp", CivilWarOp.class);
        x.alias("CivilWarPhase", CivilWarPhase.class);
        x.alias("RallyOp", RallyOp.class);
        x.alias("RallyPhase", RallyPhase.class);
        x.alias("MischiefOp", MischiefOp.class);
        x.alias("RallyDisruptionPhase", RallyDisruptionPhase.class);
        x.alias("IntrigueSubfaction", IntrigueSubfaction.class);
        x.alias("IntrigueSubfactionManager", IntrigueSubfactionManager.class);
        x.alias("IntrigueTerritory", IntrigueTerritory.class);
        x.alias("IntrigueTerritoryManager", IntrigueTerritoryManager.class);
        x.alias("TerritoryPatrolScript", TerritoryPatrolScript.class);
        x.alias("TerritoryPatrolSlot", TerritoryPatrolScript.PatrolSlot.class);
        x.alias("TerritoryPatrolSatEntry", TerritoryPatrolScript.SatelliteRouteEntry.class);
        x.alias("AmbientPatrolSpawner", TerritoryPatrolScript.AmbientPatrolSpawner.class);
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        DissidentFactions.reset();
        initServices();
        IntrigueSubfactionManager.get().bootstrapIfNeeded();
        IntrigueTerritoryManager.get().bootstrapIfNeeded();
        IntriguePeopleManager.get().refreshAll();
        ensureScripts();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        DissidentFactions.reset();
        initServices();
        IntriguePeopleManager.get().refreshAll();
        ensureScripts();
    }

    private void initServices() {
        IntrigueServices.init(
                new SectorClock(),
                IntriguePeopleManager.get(),
                IntrigueOpsManager.get(),
                new GameOpFactory(),
                IntrigueSubfactionManager.get(),
                new GameFactionHostilityChecker(),
                IntrigueTerritoryManager.get()
        );
    }

    private void ensureScripts() {
        ensurePacerScript();
        ensurePeopleScript();
        ensureOpsManagerScript();
        ensureTerritoryPatrolScript();
    }

    private void ensurePeopleScript() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_SCRIPT_KEY);

        if (!(existing instanceof IntriguePeopleScript)) {
            IntriguePeopleScript script = new IntriguePeopleScript();
            Global.getSector().addScript(script);
            data.put(IntrigueIds.PERSIST_SCRIPT_KEY, script);
        }
    }

    private void ensurePacerScript() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_PACER_SCRIPT_KEY);

        if (!(existing instanceof spinloki.Intrigue.campaign.IntriguePacerScript)) {
            spinloki.Intrigue.campaign.IntriguePacerScript script = new spinloki.Intrigue.campaign.IntriguePacerScript();
            Global.getSector().addScript(script);
            data.put(IntrigueIds.PERSIST_PACER_SCRIPT_KEY, script);
        }
    }

    private void ensureOpsManagerScript() {
        IntrigueOpsManager mgr = IntrigueOpsManager.get();

        // Always check that the manager is registered as a script.
        // After save/load or recompile, it may exist in persistent data
        // but not in the sector's EveryFrameScript list.
        boolean found = false;
        for (EveryFrameScript script : Global.getSector().getScripts()) {
            if (script == mgr) {
                found = true;
                break;
            }
        }
        if (!found) {
            Global.getSector().addScript(mgr);
        }
    }

    private void ensureTerritoryPatrolScript() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_TERRITORY_PATROL_KEY);

        if (!(existing instanceof TerritoryPatrolScript)) {
            TerritoryPatrolScript script = new TerritoryPatrolScript();
            Global.getSector().addScript(script);
            data.put(IntrigueIds.PERSIST_TERRITORY_PATROL_KEY, script);
        }
    }

}