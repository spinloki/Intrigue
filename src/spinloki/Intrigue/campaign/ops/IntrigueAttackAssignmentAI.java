package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;

/**
 * Assignment AI for Intrigue raid/attack fleets. Overrides the local
 * assignment to use ATTACK_LOCATION instead of PATROL_SYSTEM.
 */
public class IntrigueAttackAssignmentAI extends IntrigueRouteAssignmentAI {

    private final SectorEntityToken attackTarget;

    /**
     * @param fleet        the fleet
     * @param route        the route data
     * @param travelText   text for travel assignments
     * @param actionText   text for in-system attack assignments
     * @param attackTarget the specific entity to attack (market entity, etc.)
     */
    public IntrigueAttackAssignmentAI(CampaignFleetAPI fleet, RouteData route,
                                      String travelText, String actionText,
                                      SectorEntityToken attackTarget) {
        super(fleet, route, travelText, actionText);
        this.attackTarget = attackTarget;
    }

    @Override
    protected void addLocalAssignment(RouteSegment current, boolean justSpawned) {
        if (justSpawned) {
            float progress = current.getProgress();
            com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator.setLocation(
                    fleet, progress, current.from, current.getDestination());
        }

        SectorEntityToken target = attackTarget;
        if (target == null) {
            // Fall back to segment destination or system center
            if (current.from != null && current.from.getContainingLocation() instanceof StarSystemAPI) {
                target = ((StarSystemAPI) current.from.getContainingLocation()).getCenter();
            } else {
                target = current.from;
            }
        }

        fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, target,
                current.daysMax - current.elapsed, getInSystemActionText(current),
                goNextScript(current));
    }
}


