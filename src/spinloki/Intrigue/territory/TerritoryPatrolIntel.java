package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Intel for a cross-system patrol operation. Created by {@link TerritoryManager}
 * when {@link TerritoryState} launches a patrol op.
 *
 * <p>Uses RouteManager for fleet lifecycle — the fleet only physically exists when
 * the player is nearby. Implements {@link FleetEventListener} to detect fleet
 * destruction and feed the outcome back to TerritoryState.</p>
 */
public class TerritoryPatrolIntel extends BaseIntelPlugin
        implements RouteManager.RouteFleetSpawner, FleetEventListener {

    private static final Logger log = Global.getLogger(TerritoryPatrolIntel.class);

    /** Base combat FP for patrol fleets — scaled by market's COMBAT_FLEET_SIZE_MULT. */
    private static final float BASE_COMBAT_FP = 60f;
    /** Fraction of combat FP allocated to tankers (fuel for cross-system travel). */
    private static final float TANKER_FRACTION = 0.1f;
    /** Fraction of combat FP allocated to freighters (supplies). */
    private static final float FREIGHTER_FRACTION = 0.1f;
    /** Days to patrol at the destination before returning. */
    private static final float PATROL_DAYS = 25f;
    /** Days to wait at base before departure / after return. */
    private static final float PREP_DAYS = 3f;
    private static final float END_DAYS = 8f;

    private final long opId;
    private final String subfactionId;
    private final String territoryId;
    private final String originSystemId;
    private final String targetSystemId;
    private final SubfactionDef def;

    private RouteData route;
    private boolean fleetDestroyed = false;

    /**
     * Create and register a patrol intel. Immediately creates a RouteManager route.
     *
     * @param op   The ActiveOp from TerritoryState.
     * @param def  The subfaction definition.
     * @param slot The base slot the subfaction operates from.
     */
    public TerritoryPatrolIntel(ActiveOp op, SubfactionDef def, BaseSlot slot, String territoryId) {
        this.opId = op.getOpId();
        this.subfactionId = def.id;
        this.territoryId = territoryId;
        this.originSystemId = op.getOriginSystemId();
        this.targetSystemId = op.getTargetSystemId();
        this.def = def;

        MarketAPI baseMarket = findBaseMarket(slot);
        if (baseMarket == null) {
            log.error("TerritoryPatrolIntel: no market for slot " + slot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = baseMarket.getPrimaryEntity();
        SectorEntityToken target = findSystemCenter(targetSystemId);
        if (target == null) {
            log.error("TerritoryPatrolIntel: target system not found: " + targetSystemId);
            endNow();
            return;
        }

        createRoute(baseMarket, origin, target);

        Global.getSector().addScript(this);
        Global.getSector().getIntelManager().addIntel(this, true); // true = hidden from player initially
        setImportant(false);

        log.info("TerritoryPatrolIntel created: " + def.name + " patrol " +
                originSystemId + " → " + targetSystemId);
    }

    private void createRoute(MarketAPI baseMarket, SectorEntityToken origin, SectorEntityToken target) {
        OptionalFleetData extra = new OptionalFleetData(baseMarket);
        extra.factionId = subfactionId;
        extra.fleetType = FleetTypes.PATROL_MEDIUM;

        route = RouteManager.getInstance().addRoute(
                "intrigue_patrol_" + opId,
                baseMarket,
                Misc.genRandomSeed(),
                extra,
                this
        );

        // Compute explicit travel days from hyperspace distance (vanilla pattern from RuinsFleetRouteManager)
        float distLY = Misc.getDistanceLY(origin.getLocationInHyperspace(), target.getLocationInHyperspace());
        float travelDays = distLY * 1.5f;

        // Segment structure matches vanilla: prep → travel → patrol → travel → end
        route.addSegment(new RouteSegment(PREP_DAYS, origin));                      // prep at base
        route.addSegment(new RouteSegment(travelDays, origin, target));              // outbound travel
        route.addSegment(new RouteSegment(PATROL_DAYS, target));                     // patrol at destination
        route.addSegment(new RouteSegment(travelDays, target, origin));              // return travel
        route.addSegment(new RouteSegment(END_DAYS, origin));                        // end at base
    }

    // ── RouteFleetSpawner ────────────────────────────────────────────────

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        float combatFP = BASE_COMBAT_FP;
        float tankerFP = combatFP * TANKER_FRACTION;
        float freighterFP = combatFP * FREIGHTER_FRACTION;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                subfactionId,
                route.getQualityOverride(),
                FleetTypes.PATROL_MEDIUM,
                combatFP,
                freighterFP,
                tankerFP,
                0f, // transport FP
                0f, // liner FP
                0f, // utility FP
                def.fleetQualityMod
        );
        params.timestamp = route.getTimestamp();

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) return null;

        fleet.setFaction(subfactionId, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);

        fleet.getEventListeners().add(this);

        // Place fleet at market location before attaching route AI
        market.getContainingLocation().addEntity(fleet);
        fleet.setFacing((float) Math.random() * 360f);
        SectorEntityToken primary = market.getPrimaryEntity();
        fleet.setLocation(primary.getLocation().x, primary.getLocation().y);

        // PatrolRouteAI fixes vanilla's missing goNextScript on PATROL_SYSTEM assignments
        fleet.addScript(new PatrolRouteAI(fleet, route));

        log.info("TerritoryPatrolIntel: spawned " + def.name + " patrol fleet (" +
                fleet.getFleetPoints() + " FP)");

        return fleet;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) {
        return isEnded() || isEnding();
    }

    @Override
    public boolean shouldRepeat(RouteData route) {
        return false; // One patrol per intel
    }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        // Normal despawn (player moved away) — nothing special to do.
    }

    // ── FleetEventListener ───────────────────────────────────────────────

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
                                                CampaignEventListener.FleetDespawnReason reason,
                                                Object param) {
        if (reason == CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE) {
            fleetDestroyed = true;
            log.info("TerritoryPatrolIntel: " + def.name + " patrol fleet destroyed in battle");
        }
    }

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet,
                                      CampaignFleetAPI primaryWinner,
                                      BattleAPI battle) {
        // Could track battle outcomes here in the future
    }

    // ── Intel lifecycle ──────────────────────────────────────────────────

    @Override
    protected void advanceImpl(float amount) {
        // Route completed when all segments are done
        if (route != null && route.isExpired()) {
            cleanup();
        }
    }

    @Override
    protected void notifyEnded() {
        super.notifyEnded();
        if (route != null) {
            RouteManager.getInstance().removeRoute(route);
        }
        Global.getSector().removeScript(this);
    }

    private void cleanup() {
        endAfterDelay();
    }

    private void endNow() {
        setImportant(false);
        endAfterDelay(0f);
    }

    // ── Public API ───────────────────────────────────────────────────────

    public long getOpId() { return opId; }
    public String getSubfactionId() { return subfactionId; }
    public boolean wasFleetDestroyed() { return fleetDestroyed; }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Patrol";
    }

    @Override
    public String getIcon() {
        return Global.getSector().getFaction(subfactionId).getCrest();
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);

        float pad = 3f;
        Color hl = Misc.getHighlightColor();
        info.addPara("Route: %s → %s", pad, hl,
                formatSystemName(originSystemId), formatSystemName(targetSystemId));
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        Color hl = Misc.getHighlightColor();
        float pad = 10f;

        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A %s patrol fleet is operating in the " + formatSystemName(territoryId) +
                        " territory.", pad, faction.getBaseUIColor(), def.name);

        info.addPara("Route: %s → %s → return", pad, hl,
                formatSystemName(originSystemId), formatSystemName(targetSystemId));

        if (fleetDestroyed) {
            info.addPara("The patrol fleet was destroyed.", Misc.getNegativeHighlightColor(), pad);
        }
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("Intrigue");
        return tags;
    }

    @Override
    public String getSortString() {
        return "Intrigue Patrol";
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        return Global.getSector().getFaction(subfactionId);
    }

    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        SectorEntityToken target = findSystemCenter(targetSystemId);
        return target;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private MarketAPI findBaseMarket(BaseSlot slot) {
        SectorEntityToken station = findStationEntity(slot);
        if (station == null) return null;
        return station.getMarket();
    }

    private SectorEntityToken findStationEntity(BaseSlot slot) {
        if (slot.getStationEntityId() == null) return null;
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(slot.getSystemId())) {
                return system.getEntityById(slot.getStationEntityId());
            }
        }
        return null;
    }

    private SectorEntityToken findSystemCenter(String systemId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(systemId)) {
                return system.getCenter();
            }
        }
        return null;
    }

    private String formatSystemName(String systemId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(systemId)) {
                return system.getBaseName();
            }
        }
        return systemId;
    }
}
