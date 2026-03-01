package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RouteFleetAssignmentAI;

/**
 * Base assignment AI for all Intrigue mod fleets. Extends vanilla's
 * {@link RouteFleetAssignmentAI} which handles:
 * <ul>
 *   <li>Placing the fleet at the correct interpolated position on spawn</li>
 *   <li>Giving assignments for travel, local patrol, starting, and ending segments</li>
 *   <li>Advancing through route segments via goNextScript callbacks</li>
 *   <li>Returning to source and despawning when the route expires</li>
 * </ul>
 *
 * <p>Subclasses override text methods and optionally {@code addLocalAssignment}
 * for custom in-system behavior (e.g., ATTACK_LOCATION instead of PATROL).</p>
 *
 * <p>Phases with ongoing dynamic behavior (like RallyDisruptionPhase's
 * flee/chase retargeting) still run that logic in their {@code advance()}
 * method — this AI just handles the initial placement and base assignment
 * chain.</p>
 */
public class IntrigueRouteAssignmentAI extends RouteFleetAssignmentAI {

    private final String travelText;
    private final String actionText;

    /**
     * Standard constructor for most Intrigue fleets.
     *
     * @param fleet      the fleet to control
     * @param route      the route data
     * @param travelText text for travel assignments (e.g., "Traveling to scout area")
     * @param actionText text for in-system assignments (e.g., "Patrolling on behalf of X")
     */
    public IntrigueRouteAssignmentAI(CampaignFleetAPI fleet, RouteData route,
                                     String travelText, String actionText) {
        super(fleet, route);
        this.travelText = travelText;
        this.actionText = actionText;
    }

    @Override
    protected String getTravelActionText(RouteSegment segment) {
        return travelText;
    }

    @Override
    protected String getInSystemActionText(RouteSegment segment) {
        return actionText;
    }

    @Override
    protected String getStartingActionText(RouteSegment segment) {
        return actionText;
    }

    @Override
    protected String getEndingActionText(RouteSegment segment) {
        return "Returning home";
    }
}


