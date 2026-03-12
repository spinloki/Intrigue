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
import java.util.List;
import java.util.Set;

/**
 * Base class for all territory operation intels (patrol, raid, expansion, supremacy,
 * evacuation). Provides shared infrastructure for RouteManager fleet lifecycle,
 * fleet destruction detection, and intel display.
 *
 * <p>Subclasses override {@link #buildRoute} to define the specific route segments,
 * {@link #getFleetType} for the fleet type string, {@link #getBaseCombatFP} for
 * fleet sizing, and the intel display methods for custom descriptions.</p>
 */
public abstract class TerritoryOpIntel extends BaseIntelPlugin
        implements RouteManager.RouteFleetSpawner, FleetEventListener {

    private static final Logger log = Global.getLogger(TerritoryOpIntel.class);

    /** Days to wait at base before departure / after return. */
    protected static final float PREP_DAYS = 3f;
    protected static final float END_DAYS = 8f;

    /** Fraction of combat FP allocated to tankers/freighters. */
    protected static final float TANKER_FRACTION = 0.1f;
    protected static final float FREIGHTER_FRACTION = 0.1f;

    protected final long opId;
    protected final ActiveOp.OpType opType;
    protected final String subfactionId;
    protected final String territoryId;
    protected final String originSystemId;
    protected final String targetSystemId;
    protected final SubfactionDef def;

    protected RouteData route;
    protected boolean fleetDestroyed = false;

    protected TerritoryOpIntel(ActiveOp op, SubfactionDef def, String territoryId) {
        this.opId = op.getOpId();
        this.opType = op.getType();
        this.subfactionId = def.id;
        this.territoryId = territoryId;
        this.originSystemId = op.getOriginSystemId();
        this.targetSystemId = op.getTargetSystemId();
        this.def = def;
    }

    /**
     * Initialize the intel: find origin/destination entities, build the route,
     * and register with the sector. Called by subclass constructors after their
     * own field initialization.
     *
     * @param spawnMarket The market where the fleet spawns.
     * @param origin      The entity to depart from.
     * @param destination The entity to travel to.
     * @return true if initialization succeeded.
     */
    protected boolean init(MarketAPI spawnMarket, SectorEntityToken origin, SectorEntityToken destination) {
        if (spawnMarket == null || origin == null || destination == null) {
            log.error(getClass().getSimpleName() + ": init failed — null market/origin/destination");
            endNow();
            return false;
        }

        createRoute(spawnMarket, origin, destination);

        Global.getSector().addScript(this);
        Global.getSector().getIntelManager().addIntel(this, true);
        setImportant(false);

        log.info(getClass().getSimpleName() + " created: " + def.name + " " + opType +
                " " + originSystemId + " → " + targetSystemId);
        return true;
    }

    private void createRoute(MarketAPI spawnMarket, SectorEntityToken origin, SectorEntityToken destination) {
        OptionalFleetData extra = new OptionalFleetData(spawnMarket);
        extra.factionId = subfactionId;
        extra.fleetType = getFleetType();

        route = RouteManager.getInstance().addRoute(
                "intrigue_" + opType.name().toLowerCase() + "_" + opId,
                spawnMarket,
                Misc.genRandomSeed(),
                extra,
                this
        );

        buildRoute(route, origin, destination);
    }

    /**
     * Build the route segments. Subclasses define the specific travel pattern.
     *
     * @param route       The route to add segments to.
     * @param origin      The departure entity.
     * @param destination The arrival entity.
     */
    protected abstract void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination);

    /** The vanilla FleetTypes constant for this op's fleet. */
    protected abstract String getFleetType();

    /** Base combat FP for fleet creation. */
    protected abstract float getBaseCombatFP();

    /**
     * Compute travel days between two entities based on hyperspace distance.
     */
    protected float computeTravelDays(SectorEntityToken from, SectorEntityToken to) {
        float distLY = Misc.getDistanceLY(from.getLocationInHyperspace(), to.getLocationInHyperspace());
        return Math.max(distLY * 1.5f, 2f);
    }

    // ── RouteFleetSpawner ────────────────────────────────────────────────

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        float combatFP = getBaseCombatFP();
        float tankerFP = combatFP * TANKER_FRACTION;
        float freighterFP = combatFP * FREIGHTER_FRACTION;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                subfactionId,
                route.getQualityOverride(),
                getFleetType(),
                combatFP,
                freighterFP,
                tankerFP,
                0f, 0f, 0f,
                def.fleetQualityMod
        );
        params.timestamp = route.getTimestamp();

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) return null;

        fleet.setFaction(subfactionId, true);
        configureFleetMemory(fleet);
        fleet.getEventListeners().add(this);

        market.getContainingLocation().addEntity(fleet);
        fleet.setFacing((float) Math.random() * 360f);
        SectorEntityToken primary = market.getPrimaryEntity();
        fleet.setLocation(primary.getLocation().x, primary.getLocation().y);

        addFleetAI(fleet, route);

        log.info(getClass().getSimpleName() + ": spawned " + def.name + " " + opType +
                " fleet (" + fleet.getFleetPoints() + " FP)");

        return fleet;
    }

    /** Set memory flags on the spawned fleet. Override for op-specific flags. */
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
    }

    /** Add AI script to the fleet. Override for op-specific behavior. */
    protected void addFleetAI(CampaignFleetAPI fleet, RouteData route) {
        fleet.addScript(new PatrolRouteAI(fleet, route));
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) {
        return isEnded() || isEnding();
    }

    @Override
    public boolean shouldRepeat(RouteData route) {
        return false;
    }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
    }

    // ── FleetEventListener ───────────────────────────────────────────────

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
                                                CampaignEventListener.FleetDespawnReason reason,
                                                Object param) {
        if (reason == CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE) {
            fleetDestroyed = true;
            log.info(getClass().getSimpleName() + ": " + def.name + " " + opType +
                    " fleet destroyed in battle");
        }
    }

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet,
                                      CampaignFleetAPI primaryWinner,
                                      BattleAPI battle) {
    }

    // ── Intel lifecycle ──────────────────────────────────────────────────

    @Override
    protected void advanceImpl(float amount) {
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

    protected void endNow() {
        setImportant(false);
        endAfterDelay(0f);
    }

    // ── Public API ───────────────────────────────────────────────────────

    public long getOpId() { return opId; }
    public ActiveOp.OpType getOpType() { return opType; }
    public String getSubfactionId() { return subfactionId; }
    public boolean wasFleetDestroyed() { return fleetDestroyed; }

    // ── Intel display (default implementations) ──────────────────────────

    @Override
    public String getIcon() {
        return Global.getSector().getFaction(subfactionId).getCrest();
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("Intrigue");
        return tags;
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        return Global.getSector().getFaction(subfactionId);
    }

    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        return findSystemCenter(targetSystemId);
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    protected MarketAPI findBaseMarket(BaseSlot slot) {
        SectorEntityToken station = findStationEntity(slot);
        if (station == null) return null;
        return station.getMarket();
    }

    protected SectorEntityToken findStationEntity(BaseSlot slot) {
        if (slot.getStationEntityId() == null) return null;
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(slot.getSystemId())) {
                return system.getEntityById(slot.getStationEntityId());
            }
        }
        return null;
    }

    protected SectorEntityToken findSystemCenter(String systemId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(systemId)) {
                return system.getCenter();
            }
        }
        return null;
    }

    protected String formatSystemName(String systemId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(systemId)) {
                return system.getBaseName();
            }
        }
        return systemId;
    }

    /**
     * Find a market belonging to the given faction. Picks the largest one.
     * Used by expansion/supremacy/evacuation to find parent faction markets.
     */
    protected static MarketAPI findFactionMarket(String factionId) {
        MarketAPI best = null;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!market.getFactionId().equals(factionId)) continue;
            if (market.isHidden()) continue;
            if (best == null || market.getSize() > best.getSize()) {
                best = market;
            }
        }
        return best;
    }
}
