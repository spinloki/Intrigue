package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.util.*;

/**
 * Creates a subfaction base station with a hidden market at a pre-computed {@link BaseSlot}.
 *
 * <p>Mirrors the vanilla {@code PirateBaseIntel} constructor exactly:</p>
 * <ul>
 *   <li>Hidden market isolated via {@code setEconGroup(marketId)} so it doesn't
 *       participate in the global economy (no accessibility/stability penalties).</li>
 *   <li>Open market + black market submarkets.</li>
 *   <li>Industries: population, spaceport, military base, orbital station, ground defenses.</li>
 *   <li>{@code reapplyIndustries()} called after setup so industries actually take effect.</li>
 *   <li>Station is discoverable with sensor profile, tagged appropriately.</li>
 * </ul>
 */
public class BaseSpawner {

    private static final Logger log = Global.getLogger(BaseSpawner.class);

    /** Station entity type — a small orbital station, same as vanilla pirate bases. */
    private static final String STATION_ENTITY_TYPE = Entities.MAKESHIFT_STATION;

    /** Base market size for subfaction outposts. */
    private static final int BASE_MARKET_SIZE = 3;

    /** Tag applied to all Intrigue bases for easy identification. */
    public static final String TAG_INTRIGUE_BASE = "intrigueBase";

    /**
     * Spawn a subfaction base at the given slot.
     *
     * @param slot The pre-computed base slot.
     * @param def  The subfaction that will own this base.
     * @return The created station entity, or null if spawning failed.
     */
    public static SectorEntityToken spawnBase(BaseSlot slot, SubfactionDef def) {
        // --- Resolve system (by ID, not name) ---
        StarSystemAPI system = findSystemById(slot.getSystemId());
        if (system == null) {
            log.error("BaseSpawner: system '" + slot.getSystemId() + "' not found — cannot spawn base");
            return null;
        }

        SectorEntityToken orbitFocus = system.getEntityById(slot.getOrbitFocusEntityId());
        if (orbitFocus == null) {
            orbitFocus = system.getStar();
            if (orbitFocus == null) {
                log.error("BaseSpawner: no orbit focus found for slot '" + slot.getSlotId() + "'");
                return null;
            }
            log.warn("BaseSpawner: orbit focus '" + slot.getOrbitFocusEntityId() +
                    "' not found, falling back to star");
        }

        // --- Create the hidden market (before entity, matching vanilla order) ---
        String marketId = "intrigue_base_" + def.id + "_" + System.currentTimeMillis();

        MarketAPI market = Global.getFactory().createMarket(marketId, def.name + " Base", BASE_MARKET_SIZE);
        market.setSize(BASE_MARKET_SIZE);
        market.setHidden(true);
        market.getMemoryWithoutUpdate().set(MemFlags.HIDDEN_BASE_MEM_FLAG, true);

        market.setFactionId(def.id);
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);

        market.addCondition(Conditions.POPULATION_3);

        // Shared RNG for station type and orbit placement
        Random rand = new Random();

        // Core industries — matches vanilla PirateBaseIntel exactly
        market.addIndustry(Industries.POPULATION);
        market.addIndustry(Industries.SPACEPORT);
        market.addIndustry(Industries.MILITARYBASE);

        // Orbital station — tech level determined by subfaction config.
        // Supports weighted random pick (e.g. pirate base defaults) or single fixed type.
        String stationIndustry = def.pickStationIndustry(rand);
        market.addIndustry(stationIndustry);

        // Submarkets — open market + black market, matching vanilla
        market.addSubmarket(Submarkets.SUBMARKET_OPEN);
        market.addSubmarket(Submarkets.SUBMARKET_BLACK);

        // Tariff from faction settings
        market.getTariff().modifyFlat("default_tariff", market.getFaction().getTariffFraction());

        // --- Create the station entity ---
        String entityId = marketId + "_entity";
        float orbitAngle = rand.nextFloat() * 360f;
        float orbitDays = 60f + rand.nextFloat() * 60f;

        SectorEntityToken station = system.addCustomEntity(
                entityId,
                def.name + " Base",
                STATION_ENTITY_TYPE,
                def.id
        );

        station.setCircularOrbitPointingDown(orbitFocus, orbitAngle, slot.getOrbitRadius(), orbitDays);

        // Station tags and discoverability — mirrors vanilla PirateBaseIntel
        station.addTag(Tags.STATION);
        station.addTag(TAG_INTRIGUE_BASE);
        station.setSensorProfile(1f);
        station.setDiscoverable(true);
        station.getDetectedRangeMod().modifyFlat("gen", 5000f);

        // --- Wire market ↔ entity ---
        market.setPrimaryEntity(station);
        station.setMarket(market);
        station.setFaction(def.id);

        // --- Economy isolation (the key call!) ---
        // setEconGroup makes this market exist in its own isolated economy group,
        // so it doesn't take accessibility/stability penalties and doesn't cause
        // the star system name to be highlighted on the sector map.
        market.setEconGroup(market.getId());

        // Prevent decivilization
        market.getMemoryWithoutUpdate().set(DecivTracker.NO_DECIV_KEY, true);

        // Must be called after all industries are added for them to actually take effect
        market.reapplyIndustries();

        // Finish building the orbital station immediately so it's combat-ready.
        // Vanilla does this in updateStationIfNeeded() after adding the station industry.
        for (Industry ind : market.getIndustries()) {
            if (ind.getSpec().hasTag(Industries.TAG_STATION)) {
                ind.finishBuildingOrUpgrading();
                break;
            }
        }

        // Tags for identification
        market.addTag(TAG_INTRIGUE_BASE);
        market.addTag("intrigueSubfaction_" + def.id);

        // Register the market — true to match vanilla, isolation is handled by econGroup
        Global.getSector().getEconomy().addMarket(market, true);

        // Register economy listener to fill commodity shortages — mirrors vanilla
        // PirateBaseIntel.commodityUpdated(). Without this, the isolated market has
        // no supply chain and industries are crippled by shortages.
        String parentFactionName = def.parentFactionId;
        com.fs.starfarer.api.campaign.FactionAPI parentFaction =
                Global.getSector().getFaction(def.parentFactionId);
        if (parentFaction != null) {
            parentFactionName = parentFaction.getDisplayNameWithArticle();
        }
        BaseEconomyListener listener = new BaseEconomyListener(marketId,
                "Shipped in by " + parentFactionName,
                def.fleetSizeMult, def.fleetQualityMod,
                def.patrolExtraLight, def.patrolExtraMedium, def.patrolExtraHeavy);
        Global.getSector().getEconomy().addUpdateListener(listener);

        // Update the slot with station info
        slot.claim(def.id);
        slot.setStationEntityId(entityId);

        log.info("BaseSpawner: created base for " + def.name + " at " + slot.getLabel() +
                " [" + entityId + "] market [" + marketId + "] in system " + system.getBaseName());

        return station;
    }

    /**
     * Find a star system by its ID. We iterate all systems because
     * {@code Global.getSector().getStarSystem()} searches by name, not ID.
     */
    private static StarSystemAPI findSystemById(String systemId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(systemId)) {
                return system;
            }
        }
        return null;
    }
}

