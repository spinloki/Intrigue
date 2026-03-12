package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;

/**
 * Intel for an expansion or supremacy operation. A large fleet group spawns at
 * a parent faction market, travels to the subfaction's station in the territory,
 * and orbits it in a show of force.
 *
 * <p>EXPANSION: ESTABLISHED → FORTIFIED. SUPREMACY: FORTIFIED → DOMINANT.</p>
 *
 * <p>The fleet is one-way — it arrives and integrates into the station's garrison.
 * No return trip.</p>
 */
public class TerritoryReinforcementIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryReinforcementIntel.class);

    /** Expansion fleets are larger than patrols. Supremacy fleets are the biggest. */
    private static final float EXPANSION_COMBAT_FP = 100f;
    private static final float SUPREMACY_COMBAT_FP = 150f;

    /** Days the fleet orbits the station after arrival (show of force). */
    private static final float ORBIT_DAYS = 20f;

    private final boolean isSupremacy;

    /**
     * @param op          The EXPANSION or SUPREMACY ActiveOp.
     * @param def         The subfaction definition.
     * @param slot        The subfaction's base slot (fleet destination).
     * @param territoryId Territory this reinforcement targets.
     */
    public TerritoryReinforcementIntel(ActiveOp op, SubfactionDef def,
                                        BaseSlot slot, String territoryId) {
        super(op, def, territoryId);
        this.isSupremacy = (op.getType() == ActiveOp.OpType.SUPREMACY);

        // Find a parent faction market to spawn the fleet from
        MarketAPI parentMarket = findFactionMarket(def.parentFactionId);
        if (parentMarket == null) {
            log.error("TerritoryReinforcementIntel: no parent market for " + def.parentFactionId);
            endNow();
            return;
        }

        SectorEntityToken origin = parentMarket.getPrimaryEntity();

        // Destination is the subfaction's station
        SectorEntityToken destination = findStationEntity(slot);
        if (destination == null) {
            destination = findSystemCenter(slot.getSystemId());
        }
        if (destination == null) {
            log.error("TerritoryReinforcementIntel: destination not found for " + slot.getSlotId());
            endNow();
            return;
        }

        init(parentMarket, origin, destination);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        float travelDays = computeTravelDays(origin, destination);
        route.addSegment(new RouteSegment(PREP_DAYS, origin));
        route.addSegment(new RouteSegment(travelDays, origin, destination));
        route.addSegment(new RouteSegment(ORBIT_DAYS, destination));
        // No return trip — fleet integrates into the station garrison
    }

    @Override
    protected String getFleetType() {
        return FleetTypes.PATROL_LARGE;
    }

    @Override
    protected float getBaseCombatFP() {
        return isSupremacy ? SUPREMACY_COMBAT_FP : EXPANSION_COMBAT_FP;
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + (isSupremacy ? " Supremacy Fleet" : " Expansion Fleet");
    }

    @Override
    public String getSortString() {
        return isSupremacy ? "Intrigue Supremacy" : "Intrigue Expansion";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Reinforcing %s territory", 3f, Misc.getHighlightColor(),
                formatSystemName(territoryId));
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        String typeDesc = isSupremacy
                ? "A major %s fleet is moving to assert dominance in"
                : "A %s reinforcement fleet is expanding operations in";
        info.addPara(typeDesc + " the " + formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name);
        info.addPara("Origin: %s", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId));
        info.addPara("Destination: %s", pad, Misc.getHighlightColor(),
                formatSystemName(targetSystemId));
        if (fleetDestroyed) {
            info.addPara("The fleet was destroyed en route.",
                    Misc.getNegativeHighlightColor(), pad);
        }
    }
}
