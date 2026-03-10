package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

import java.io.Serializable;

/**
 * Ensures a hidden subfaction base never suffers commodity shortages and has
 * appropriately-sized, appropriately-quality patrol fleets.
 *
 * <p>Mirrors the vanilla {@code PirateBaseIntel} approach:</p>
 * <ul>
 *   <li>{@link #commodityUpdated(String)} fills demand–supply gaps so industries
 *       are never crippled by isolation-induced shortages.</li>
 *   <li>{@link #economyUpdated()} applies fleet size and ship quality modifiers
 *       to the market so that patrols spawned by the military base are sized
 *       and equipped appropriately for the subfaction.</li>
 * </ul>
 *
 * <p>Implements {@link EconomyAPI.EconomyUpdateListener} and is registered via
 * {@code Global.getSector().getEconomy().addUpdateListener(this)} at base creation.
 * It auto-expires (and is removed by the economy) when the market is removed.</p>
 */
public class BaseEconomyListener implements EconomyAPI.EconomyUpdateListener, Serializable {

    private static final long serialVersionUID = 3L;

    /** Stat modifier key for fleet size and quality — unique per base market. */
    private static final String MOD_ID = "intrigueBase";

    private final String marketId;
    private final String supplyDescription;

    /** Multiplier applied to {@code Stats.COMBAT_FLEET_SIZE_MULT}. */
    private final float fleetSizeMult;

    /** Flat modifier applied to {@code Stats.FLEET_QUALITY_MOD}. */
    private final float fleetQualityMod;

    /** Flat additions to patrol counts (light / medium / heavy). */
    private final int patrolExtraLight;
    private final int patrolExtraMedium;
    private final int patrolExtraHeavy;

    public BaseEconomyListener(String marketId, String supplyDescription,
                               float fleetSizeMult, float fleetQualityMod,
                               int patrolExtraLight, int patrolExtraMedium, int patrolExtraHeavy) {
        this.marketId = marketId;
        this.supplyDescription = supplyDescription;
        this.fleetSizeMult = fleetSizeMult;
        this.fleetQualityMod = fleetQualityMod;
        this.patrolExtraLight = patrolExtraLight;
        this.patrolExtraMedium = patrolExtraMedium;
        this.patrolExtraHeavy = patrolExtraHeavy;
    }

    /**
     * Called each economy tick for each commodity. If demand exceeds available
     * supply, we fill the gap so the base is always fully functional.
     * Directly mirrors PirateBaseIntel.commodityUpdated().
     */
    @Override
    public void commodityUpdated(String commodityId) {
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        if (market == null) return;

        CommodityOnMarketAPI com = market.getCommodityData(commodityId);
        String modId = marketId;

        // Get current modifier we've applied (if any)
        int curr = 0;
        MutableStat.StatMod mod = com.getAvailableStat().getFlatStatMod(modId);
        if (mod != null) {
            curr = Math.round(mod.value);
        }

        // Calculate available supply without penalties (same logic as vanilla)
        int avWithoutPenalties = (int) Math.round(com.getAvailableStat().getBaseValue());
        for (MutableStat.StatMod m : com.getAvailableStat().getFlatMods().values()) {
            if (m.value < 0) continue;
            avWithoutPenalties += (int) Math.round(m.value);
        }

        int a = avWithoutPenalties - curr;
        int d = com.getMaxDemand();
        if (d > a) {
            int supply = Math.max(1, d - a);
            com.getAvailableStat().modifyFlat(modId, supply, supplyDescription);
        }
    }

    /**
     * Called after all commodity updates are complete. Applies fleet size and
     * ship quality modifiers to the market — mirrors how vanilla PirateBaseIntel
     * adjusts patrol strength in its own economyUpdated().
     *
     * <p>Without these modifiers, a size-3 hidden market with a military base
     * produces pathetically small, heavily D-modded patrols.</p>
     */
    @Override
    public void economyUpdated() {
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        if (market == null) return;

        // Fleet size multiplier — makes patrols proportionally larger.
        // This is the same stat that vanilla pirate bases modify.
        market.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT)
                .modifyFlat(MOD_ID, fleetSizeMult);

        // Ship quality modifier — reduces D-mods on spawned ships.
        // Without this, the low market size produces almost entirely D-modded ships.
        if (fleetQualityMod != 0f) {
            market.getStats().getDynamic().getMod(Stats.FLEET_QUALITY_MOD)
                    .modifyFlat(MOD_ID, fleetQualityMod);
        }

        // Patrol count modifiers — add extra patrols beyond the military base baseline.
        // These are the same stats that vanilla uses for patrol HQ / military base scaling.
        if (patrolExtraLight != 0) {
            market.getStats().getDynamic().getMod(Stats.PATROL_NUM_LIGHT_MOD)
                    .modifyFlat(MOD_ID, patrolExtraLight);
        }
        if (patrolExtraMedium != 0) {
            market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD)
                    .modifyFlat(MOD_ID, patrolExtraMedium);
        }
        if (patrolExtraHeavy != 0) {
            market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD)
                    .modifyFlat(MOD_ID, patrolExtraHeavy);
        }
    }

    @Override
    public boolean isEconomyListenerExpired() {
        // Expire when the market no longer exists
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        return market == null;
    }
}

