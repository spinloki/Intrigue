package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator.AddedEntity;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator.EntityLocation;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator.LocationType;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase that establishes a hidden base for a subfaction.
 *
 * System selection is delegated to a {@link SystemPicker}, allowing different
 * subfaction types to use different strategies (criminals prefer near-core
 * systems with few salvageables, political subfactions may prefer frontier
 * systems near friendly territory, etc.).
 *
 * Station placement uses the same logic as vanilla PirateBaseIntel:
 * BaseThemeGenerator.getLocations() picks a suitable spot (asteroid field,
 * ring system, gas giant orbit, planet orbit, or nebula), then
 * addNonSalvageEntity() creates a makeshift_station there with proper
 * orbital parameters.
 */
public class EstablishBasePhase implements OpPhase, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(EstablishBasePhase.class.getName());

    private static final float SETUP_DAYS = 45f;
    private static final int BASE_SIZE = 3;

    private final String factionId;
    private final String subfactionId;
    private final String subfactionName;
    private final SystemPicker systemPicker;

    private float elapsed = 0f;
    private boolean done = false;
    private boolean succeeded = false;
    private String createdMarketId;

    /**
     * @param factionId       faction ID (e.g. "pirates")
     * @param subfactionId    intrigue subfaction ID
     * @param subfactionName  display name for the base
     * @param systemPicker    strategy for choosing which system to build in
     */
    public EstablishBasePhase(String factionId, String subfactionId, String subfactionName,
                              SystemPicker systemPicker) {
        this.factionId = factionId;
        this.subfactionId = subfactionId;
        this.subfactionName = subfactionName != null ? subfactionName : subfactionId;
        this.systemPicker = systemPicker;
    }

    @Override
    public void advance(float days) {
        if (done) return;

        if (!PhaseUtil.isSectorAvailable()) {
            done = true;
            succeeded = false;
            return;
        }

        elapsed += days;
        if (elapsed < SETUP_DAYS) return;

        try {
            createdMarketId = createBase();
            succeeded = (createdMarketId != null);
        } catch (Exception e) {
            log.severe("EstablishBasePhase: failed to create base: " + e.getMessage());
            succeeded = false;
        }
        done = true;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!done) {
            float remaining = Math.max(0, SETUP_DAYS - elapsed);
            return String.format("Scouting location (%.0f days remaining)", remaining);
        }
        return succeeded ? "Base established" : "Failed to find location";
    }

    public boolean didSucceed() {
        return succeeded;
    }

    public String getCreatedMarketId() {
        return createdMarketId;
    }

    // ── Base creation - mirrors vanilla PirateBaseIntel ──────────────────

    private String createBase() {
        StarSystemAPI sys = systemPicker.pick();
        if (sys == null) {
            log.warning("EstablishBasePhase: SystemPicker returned no system.");
            return null;
        }

        String result = tryCreateBaseInSystem(sys);
        if (result != null) return result;

        log.warning("EstablishBasePhase: failed to place station in " + sys.getBaseName());
        return null;
    }

    /**
     * Try to place a station in the given system using BaseThemeGenerator,
     * exactly mirroring PirateBaseIntel's constructor logic:
     *
     *   1. Build a weighted LocationType map (same weights vanilla uses)
     *   2. Call BaseThemeGenerator.getLocations() to get candidate spots
     *   3. Pick one, call addNonSalvageEntity() to create the makeshift_station
     *   4. Apply convertOrbitWithSpin() for the slow spin vanilla uses
     *   5. Create and attach the hidden market
     */
    private String tryCreateBaseInSystem(StarSystemAPI sys) {
        Random rng = new Random();

        // Same location type weights as vanilla PirateBaseIntel
        LinkedHashMap<LocationType, Float> weights = new LinkedHashMap<>();
        weights.put(LocationType.IN_ASTEROID_BELT, 10f);
        weights.put(LocationType.IN_ASTEROID_FIELD, 10f);
        weights.put(LocationType.IN_RING, 10f);
        weights.put(LocationType.IN_SMALL_NEBULA, 10f);
        weights.put(LocationType.GAS_GIANT_ORBIT, 10f);
        weights.put(LocationType.PLANET_ORBIT, 10f);

        WeightedRandomPicker<EntityLocation> locs =
                BaseThemeGenerator.getLocations(rng, sys, null, 100f, weights);

        EntityLocation loc = locs.pick();
        if (loc == null) return null;

        // Create the station entity - same as vanilla: makeshift_station
        AddedEntity added = BaseThemeGenerator.addNonSalvageEntity(
                sys, loc, Entities.MAKESHIFT_STATION, factionId);
        if (added == null || added.entity == null) return null;

        // Apply slow spin, same as vanilla PirateBaseIntel (-5f)
        BaseThemeGenerator.convertOrbitWithSpin(added.entity, -5f);

        SectorEntityToken station = added.entity;

        // Tag for identification
        station.addTag(Tags.STATION);
        station.addTag("intrigueBase");
        station.addTag("intrigueSubfaction_" + subfactionId);
        station.setSensorProfile(1f);
        station.setDiscoverable(true);
        station.getDetectedRangeMod().modifyFlat("gen", 5000f);

        // Create hidden market attached to the station
        String marketId = "intrigue_base_" + subfactionId + "_" + System.currentTimeMillis();

        MarketAPI market = Global.getFactory().createMarket(marketId, subfactionName + " Base", BASE_SIZE);
        market.setFactionId(factionId);
        market.setPrimaryEntity(station);
        market.setHidden(true);
        market.setHasSpaceport(true);

        market.addCondition(Conditions.POPULATION_3);

        // Industries - core infrastructure
        market.addIndustry(Industries.POPULATION);
        market.addIndustry(Industries.SPACEPORT);

        // Military - patrols and fleet production
        market.addIndustry(Industries.MILITARYBASE);

        // Structures - waystation for supply/fuel and an orbital station for defense
        market.addIndustry(Industries.WAYSTATION);
        market.addIndustry(Industries.ORBITALSTATION);
        market.addIndustry(Industries.GROUNDDEFENSES);

        market.setHasWaystation(true);

        market.addSubmarket(Submarkets.SUBMARKET_BLACK);
        market.addSubmarket(Submarkets.SUBMARKET_STORAGE);

        market.addTag("intrigueBase");
        market.addTag("intrigueSubfaction_" + subfactionId);

        Global.getSector().getEconomy().addMarket(market, false);
        station.setMarket(market);

        log.info("EstablishBasePhase: created station + market '" + marketId
                + "' in " + sys.getBaseName()
                + " (locType=" + loc.type + ")"
                + " for faction=" + factionId + " subfaction=" + subfactionId);

        return marketId;
    }

}


