package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

import java.util.*;

/**
 * Civil war phase: large fleets from the same faction fight across the
 * faction's core systems (around markets the faction owns).
 *
 * <p>Extends {@link FactionBattlePhase} with civil-war-specific configuration,
 * larger fleets, POI deduplication, and core-system battleground selection.</p>
 */
public class CivilWarPhase extends FactionBattlePhase {

    private static final long serialVersionUID = 1L;

    /** Track which POIs have been claimed by a pair to avoid duplicates. */
    private final Set<String> claimedPOIs = new HashSet<>();

    public CivilWarPhase(String factionId, String sourceMarketIdA,
                         String sourceMarketIdB, int fleetFP, String subfactionName) {
        super(factionId, sourceMarketIdA, factionId, sourceMarketIdB,
                fleetFP, subfactionName,
                /* minTicks */ 4, /* maxTicks */ 6);
    }

    // ── Configuration ───────────────────────────────────────────────────

    @Override protected String routeSourcePrefix() { return "intrigue_civilwar_"; }
    @Override protected String fleetType() { return FleetTypes.PATROL_LARGE; }
    @Override protected float travelDays() { return 30f; }
    @Override protected float fightDays() { return 15f; }
    @Override protected int minPairsPerTick() { return 3; }
    @Override protected int maxPairsPerTick() { return 5; }
    @Override protected float tickIntervalDays() { return 4f; }
    @Override protected String fleetALabel() { return "Loyalists"; }
    @Override protected String fleetBLabel() { return "Rebels"; }

    @Override
    protected String travelToSystemText(String systemName, String otherFleetName) {
        return "Marching to " + systemName + " with " + otherFleetName;
    }

    @Override
    protected String travelToTargetText(String targetName, String otherFleetName) {
        return "Advancing on " + targetName + " with " + otherFleetName;
    }

    @Override
    protected String fightText(String otherFleetName) {
        return "Fighting for control with " + otherFleetName;
    }

    @Override
    protected String waitText(String targetName, String otherFleetName) {
        return "Maintaining control over " + targetName;
    }

    @Override protected String statusNotStarted() { return "War brewing"; }
    @Override protected String statusDone() { return "Civil war ended"; }
    @Override
    protected String statusEngaged(int count) {
        return "Civil war raging (" + count + " battles)";
    }
    @Override
    protected String statusEnRoute() { return "Forces mobilizing"; }

    @Override
    protected void onPairResolved(FleetPair pair) {
        // Free the POI for future waves
        if (pair.meetingSystemId != null && pair.meetingEntityId != null) {
            claimedPOIs.remove(pair.meetingSystemId + ":" + pair.meetingEntityId);
        }
    }

    // ── Battleground selection ──────────────────────────────────────────

    @Override
    protected SectorEntityToken pickBattleground() {
        // Collect all systems where this faction has markets
        List<StarSystemAPI> factionSystems = new ArrayList<>();
        Set<String> factionSystemIds = new HashSet<>();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market == null || market.getPrimaryEntity() == null) continue;
            if (!factionIdA.equals(market.getFactionId())) continue;
            LocationAPI loc = market.getPrimaryEntity().getContainingLocation();
            if (loc instanceof StarSystemAPI) {
                StarSystemAPI sys = (StarSystemAPI) loc;
                if (factionSystemIds.add(sys.getId())) {
                    factionSystems.add(sys);
                }
            }
        }

        if (factionSystems.isEmpty()) return null;

        Random rng = new Random();
        Collections.shuffle(factionSystems, rng);

        // First pass: unclaimed POIs in faction systems
        for (StarSystemAPI sys : factionSystems) {
            SectorEntityToken poi = pickUnclaimedPOI(sys, rng);
            if (poi != null) {
                claimPOI(sys, poi);
                return poi;
            }
        }

        // Second pass: nearby uninhabited systems
        StarSystemAPI homeSys = factionSystems.get(0);
        float homeX = homeSys.getLocation().x;
        float homeY = homeSys.getLocation().y;

        List<StarSystemAPI> nearbySystems = new ArrayList<>();
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            if (factionSystemIds.contains(sys.getId())) continue;
            if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
            float dx = sys.getLocation().x - homeX;
            float dy = sys.getLocation().y - homeY;
            if (Math.sqrt(dx * dx + dy * dy) < 10000f) {
                nearbySystems.add(sys);
            }
        }
        Collections.shuffle(nearbySystems, rng);

        for (StarSystemAPI sys : nearbySystems) {
            SectorEntityToken poi = pickUnclaimedPOI(sys, rng);
            if (poi != null) {
                claimPOI(sys, poi);
                return poi;
            }
        }

        // Final fallback: a faction system center (even if "claimed")
        return factionSystems.get(rng.nextInt(factionSystems.size())).getCenter();
    }

    private SectorEntityToken pickUnclaimedPOI(StarSystemAPI system, Random rng) {
        List<SectorEntityToken> candidates = new ArrayList<>();

        for (PlanetAPI planet : system.getPlanets()) {
            if (planet.isStar()) continue;
            if (hasFactionMarket(planet)) continue;
            if (isClaimed(system, planet)) continue;
            candidates.add(planet);
        }
        for (SectorEntityToken entity : system.getEntitiesWithTag(Tags.STATION)) {
            if (hasFactionMarket(entity)) continue;
            if (isClaimed(system, entity)) continue;
            candidates.add(entity);
        }
        for (SectorEntityToken jp : system.getEntitiesWithTag(Tags.JUMP_POINT)) {
            if (isClaimed(system, jp)) continue;
            candidates.add(jp);
        }

        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private boolean isClaimed(StarSystemAPI system, SectorEntityToken entity) {
        return claimedPOIs.contains(system.getId() + ":" + entity.getId());
    }

    private void claimPOI(StarSystemAPI system, SectorEntityToken entity) {
        claimedPOIs.add(system.getId() + ":" + entity.getId());
    }
}



