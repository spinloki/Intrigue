package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;

/**
 * Intel for an arms shipment during a Proxy Support entanglement. The backer
 * subfaction sends a covert supply convoy to the backed subfaction's base.
 *
 * <p>The convoy flies as an {@link Factions#INDEPENDENT} trade fleet with
 * transponder off and low rep impact — it looks like an unremarkable
 * Independent freighter run. The player can identify the convoy via intel
 * and destroy it to fail the op immediately.</p>
 *
 * <p>This is a round-trip: backer base → backed base → return.</p>
 */
public class TerritoryArmsShipmentIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryArmsShipmentIntel.class);

    /** Minimal combat escort — this is a soft target. */
    private static final float COMBAT_FP = 20f;

    /** Heavy freighter allocation for the cargo. */
    private static final float FREIGHTER_FP = 60f;

    /** Days spent at the backed base unloading. */
    private static final float UNLOAD_DAYS = 5f;

    private final String backedSubfactionId;
    private final String backedSubfactionName;

    /**
     * @param op              The ARMS_SHIPMENT ActiveOp.
     * @param backerDef       The subfaction sending the arms (backer).
     * @param backerSlot      The backer's base slot (origin).
     * @param backedDef       The subfaction receiving the arms (backed).
     * @param backedSlot      The backed subfaction's base slot (destination).
     * @param territoryId     Territory this shipment operates in.
     */
    public TerritoryArmsShipmentIntel(ActiveOp op, SubfactionDef backerDef,
                                       BaseSlot backerSlot, SubfactionDef backedDef,
                                       BaseSlot backedSlot, String territoryId) {
        super(op, backerDef, territoryId);
        this.backedSubfactionId = backedDef.id;
        this.backedSubfactionName = backedDef.name;

        MarketAPI backerMarket = findBaseMarket(backerSlot);
        if (backerMarket == null) {
            log.error("TerritoryArmsShipmentIntel: no market for backer slot " + backerSlot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = backerMarket.getPrimaryEntity();

        // Destination is the backed subfaction's station
        SectorEntityToken destination = findStationEntity(backedSlot);
        if (destination == null) {
            destination = findSystemCenter(backedSlot.getSystemId());
        }
        if (destination == null) {
            log.error("TerritoryArmsShipmentIntel: backed base not found for " + backedDef.id);
            endNow();
            return;
        }

        init(backerMarket, origin, destination);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        float travelDays = computeTravelDays(origin, destination);
        route.addSegment(new RouteSegment(PREP_DAYS, origin));
        route.addSegment(new RouteSegment(travelDays, origin, destination));
        route.addSegment(new RouteSegment(UNLOAD_DAYS, destination));
        route.addSegment(new RouteSegment(travelDays, destination, origin));
        route.addSegment(new RouteSegment(END_DAYS, origin));
    }

    @Override
    protected String getFleetType() {
        return FleetTypes.TRADE_SMALL;
    }

    @Override
    protected float getBaseCombatFP() {
        return COMBAT_FP;
    }

    /**
     * Override fleet spawning to create an Independent convoy instead of a
     * subfaction fleet. Uses Independent faction for ship composition and
     * sets covert memory flags.
     */
    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                Factions.INDEPENDENT,
                route.getQualityOverride(),
                FleetTypes.TRADE_SMALL,
                COMBAT_FP,
                FREIGHTER_FP,
                0f,     // no tankers
                0f, 0f, 0f,
                0f      // no quality bonus
        );
        params.timestamp = route.getTimestamp();

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) return null;

        // Fly as Independent — looks like a regular trade fleet
        fleet.setFaction(Factions.INDEPENDENT, true);
        configureFleetMemory(fleet);
        fleet.getEventListeners().add(this);

        market.getContainingLocation().addEntity(fleet);
        fleet.setFacing((float) Math.random() * 360f);
        SectorEntityToken primary = market.getPrimaryEntity();
        fleet.setLocation(primary.getLocation().x, primary.getLocation().y);

        addFleetAI(fleet, route);

        log.info("TerritoryArmsShipmentIntel: spawned covert convoy (" +
                fleet.getFleetPoints() + " FP) for " + def.name +
                " → " + backedSubfactionName);

        return fleet;
    }

    @Override
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        // No PATROL_FLEET flag — this is a covert trade fleet
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
        // Low rep impact when destroyed — mirrors vanilla smuggler handling
        fleet.getMemoryWithoutUpdate().set("$lowRepImpact", true);
        // Smuggler flag: transponder off, avoids patrols
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_SMUGGLER, true);
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Arms Shipment";
    }

    @Override
    public String getSortString() {
        return "Intrigue Arms Shipment";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Supplying: %s", 3f, Misc.getHighlightColor(), backedSubfactionName);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A covert supply convoy is transporting arms from the %s " +
                        "to the %s.",
                pad, faction.getBaseUIColor(), def.name, backedSubfactionName);
        info.addPara("The convoy is disguised as an Independent trade fleet.",
                Misc.getGrayColor(), pad);
        info.addPara("Route: %s → %s → return", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId), formatSystemName(targetSystemId));
        if (fleetDestroyed) {
            info.addPara("The convoy was intercepted and destroyed.",
                    Misc.getNegativeHighlightColor(), pad);
        }
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        // Show as the backer subfaction's colors in intel, not Independent
        return Global.getSector().getFaction(subfactionId);
    }

    public String getBackedSubfactionId() {
        return backedSubfactionId;
    }
}
