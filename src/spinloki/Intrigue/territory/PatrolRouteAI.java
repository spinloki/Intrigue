package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RouteFleetAssignmentAI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.util.Random;

/**
 * Custom assignment AI for territory patrol fleets. Instead of issuing a single
 * long {@code PATROL_SYSTEM}, this chains short visits to points of interest
 * within the target system — jump points, planets, stations, and orbital entities.
 *
 * <p>Follows the same re-pick pattern as vanilla {@code ScavengerFleetAssignmentAI}:
 * each visit is a short assignment (3-8 days). When it expires, {@code pickNext()}
 * calls {@code addLocalAssignment()} again, which picks a new target. This repeats
 * until the segment's time budget is exhausted, at which point {@code goNextScript}
 * fires and the route advances to the return-travel segment.</p>
 */
public class PatrolRouteAI extends RouteFleetAssignmentAI {

    private Random random;

    private Random getRandom() {
        if (random == null) random = new Random();
        return random;
    }

    public PatrolRouteAI(CampaignFleetAPI fleet, RouteData route) {
        super(fleet, route);
    }

    @Override
    protected String getInSystemActionText(RouteSegment segment) {
        return "patrolling";
    }

    @Override
    protected void addLocalAssignment(final RouteSegment current, boolean justSpawned) {
        if (justSpawned) {
            float progress = current.getProgress();
            RouteLocationCalculator.setLocation(fleet, progress,
                    current.from, current.getDestination());
        }

        float timeLeft = current.daysMax - current.elapsed;

        // Segment expired — advance the route
        if (timeLeft <= 0.5f) {
            fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, current.from,
                    0.5f, "preparing to depart",
                    goNextScript(current));
            return;
        }

        // Pick a point of interest to visit (80% chance), or generic patrol (20%)
        if (getRandom().nextFloat() < 0.8f && current.from.getContainingLocation() instanceof StarSystemAPI) {
            StarSystemAPI system = (StarSystemAPI) current.from.getContainingLocation();
            SectorEntityToken target = pickPatrolTarget(system);
            if (target != null) {
                if (justSpawned) {
                    fleet.setLocation(target.getLocation().x, target.getLocation().y);
                }

                // Travel time + dwell time at the target
                float speed = Misc.getSpeedForBurnLevel(8);
                float dist = Misc.getDistance(fleet.getLocation(), target.getLocation());
                float travelDays = dist / speed / Global.getSector().getClock().getSecondsPerDay();
                float dwellDays = 3f + 5f * getRandom().nextFloat();
                float visitDays = Math.min(travelDays + dwellDays, timeLeft);

                String action = "investigating " + target.getName();
                fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, target, visitDays, action);
                return;
            }
        }

        // Fallback: generic system patrol for a short stint
        float patrolDays = Math.min(5f + 5f * getRandom().nextFloat(), timeLeft);
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, null, patrolDays, "patrolling");
    }

    /**
     * Pick a militarily-relevant target in the system: jump points (strategic chokepoints),
     * planets (population/resources), stations, and orbital entities.
     */
    private SectorEntityToken pickPatrolTarget(StarSystemAPI system) {
        WeightedRandomPicker<SectorEntityToken> picker = new WeightedRandomPicker<>(getRandom());

        // Jump points — strategic chokepoints, high priority
        for (SectorEntityToken jp : system.getJumpPoints()) {
            picker.add(jp, 3f);
        }

        // Planets — patrol presence around inhabited or resource-rich worlds
        for (PlanetAPI planet : system.getPlanets()) {
            if (planet.isStar()) continue;
            float w = 1f;
            if (planet.getMarket() != null && planet.getMarket().getSize() > 0) {
                w = 2f; // Inhabited planets are higher priority
            }
            picker.add(planet, w);
        }

        // Stations and other custom entities (comm relays, nav buoys, etc.)
        for (SectorEntityToken entity : system.getAllEntities()) {
            if (entity instanceof PlanetAPI) continue;
            if (entity.getMarket() != null) {
                picker.add(entity, 2f);
            } else if (entity.hasTag("objective")) {
                picker.add(entity, 1.5f); // Comm relays, nav buoys, sensor arrays
            }
        }

        return picker.pick();
    }
}
