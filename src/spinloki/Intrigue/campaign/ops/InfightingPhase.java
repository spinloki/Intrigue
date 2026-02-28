package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.*;

/**
 * Infighting phase: paired fleets from a subfaction travel to a point of
 * interest in a territory (or near home) to settle their differences.
 *
 * <p>Extends {@link FactionBattlePhase} with infighting-specific configuration
 * and battleground selection (territory constellations or nearby systems).</p>
 */
public class InfightingPhase extends FactionBattlePhase {

    private static final long serialVersionUID = 1L;

    private final String territoryId;

    public InfightingPhase(String factionId, String sourceMarketId,
                           String territoryId, int fleetFP, String subfactionName) {
        super(factionId, sourceMarketId, fleetFP, subfactionName,
                /* minTicks */ 3, /* maxTicks */ 5);
        this.territoryId = territoryId;
    }

    // ── Configuration ───────────────────────────────────────────────────

    @Override protected String routeSourcePrefix() { return "intrigue_infighting_"; }
    @Override protected String fleetType() { return FleetTypes.PATROL_MEDIUM; }
    @Override protected float travelDays() { return 30f; }
    @Override protected float fightDays() { return 10f; }
    @Override protected int minPairsPerTick() { return 2; }
    @Override protected int maxPairsPerTick() { return 4; }
    @Override protected float tickIntervalDays() { return 5f; }
    @Override protected String fleetALabel() { return "Loyalists"; }
    @Override protected String fleetBLabel() { return "Dissidents"; }

    @Override
    protected String fightText(String otherFleetName) {
        return "Settling their differences with " + otherFleetName;
    }

    @Override protected String statusNotStarted() { return "Tensions rising"; }
    @Override protected String statusDone() { return "Infighting subsided"; }
    @Override
    protected String statusEngaged(int count) {
        return "Infighting in progress (" + count + " engagements)";
    }
    @Override protected String statusEnRoute() { return "Dissidents en route"; }

    // ── Battleground selection ──────────────────────────────────────────

    @Override
    protected SectorEntityToken pickBattleground() {
        StarSystemAPI system = pickSystem();
        if (system == null) return null;
        return pickPOIInSystem(system);
    }

    private StarSystemAPI pickSystem() {
        if (territoryId != null) {
            return pickTerritorySystem();
        } else {
            return pickNearbySystem();
        }
    }

    private StarSystemAPI pickTerritorySystem() {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        IntrigueTerritory territory = territories.getById(territoryId);
        if (territory == null) return null;

        Set<String> constellationNames = new HashSet<>(territory.getConstellationNames());
        List<StarSystemAPI> systems = new ArrayList<>();

        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys.hasTag(Tags.THEME_CORE) || sys.hasTag(Tags.THEME_CORE_POPULATED)) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            if (sys.getConstellation() == null) continue;
            if (constellationNames.contains(sys.getConstellation().getName())) {
                systems.add(sys);
            }
        }

        if (systems.isEmpty()) return null;
        return systems.get(new Random().nextInt(systems.size()));
    }

    private StarSystemAPI pickNearbySystem() {
        MarketAPI source = Global.getSector().getEconomy().getMarket(sourceMarketIdA);
        if (source == null || source.getPrimaryEntity() == null) return null;
        LocationAPI homeLoc = source.getPrimaryEntity().getContainingLocation();
        if (!(homeLoc instanceof StarSystemAPI)) return null;

        StarSystemAPI homeSystem = (StarSystemAPI) homeLoc;
        float homeX = homeSystem.getLocation().x;
        float homeY = homeSystem.getLocation().y;

        List<StarSystemAPI> candidates = new ArrayList<>();
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (sys == homeSystem) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            float dx = sys.getLocation().x - homeX;
            float dy = sys.getLocation().y - homeY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 10000f) {
                candidates.add(sys);
            }
        }

        if (candidates.isEmpty()) return homeSystem;
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private SectorEntityToken pickPOIInSystem(StarSystemAPI system) {
        List<SectorEntityToken> pois = new ArrayList<>();
        for (PlanetAPI planet : system.getPlanets()) {
            if (!planet.isStar() && !hasFactionMarket(planet)) pois.add(planet);
        }
        for (SectorEntityToken entity : system.getEntitiesWithTag(Tags.STATION)) {
            if (!hasFactionMarket(entity)) pois.add(entity);
        }
        for (SectorEntityToken jp : system.getEntitiesWithTag(Tags.JUMP_POINT)) {
            pois.add(jp);
        }

        if (!pois.isEmpty()) {
            return pois.get(new Random().nextInt(pois.size()));
        }
        return system.getCenter();
    }
}

