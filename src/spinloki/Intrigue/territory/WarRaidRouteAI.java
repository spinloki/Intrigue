package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RouteFleetAssignmentAI;

/**
 * Custom assignment AI for war-raid hunter fleets. When the fleet reaches
 * the target system, it actively hunts fleets belonging to the target
 * subfaction using {@link FleetAssignment#INTERCEPT}. If no target is
 * found, it patrols the system while waiting.
 */
public class WarRaidRouteAI extends RouteFleetAssignmentAI {

    private final String targetSubfactionId;

    public WarRaidRouteAI(CampaignFleetAPI fleet, RouteData route, String targetSubfactionId) {
        super(fleet, route);
        this.targetSubfactionId = targetSubfactionId;
    }

    @Override
    protected String getInSystemActionText(RouteSegment segment) {
        return "hunting";
    }

    @Override
    protected void addLocalAssignment(final RouteSegment current, boolean justSpawned) {
        if (justSpawned) {
            float progress = current.getProgress();
            RouteLocationCalculator.setLocation(fleet, progress,
                    current.from, current.getDestination());
        }

        float timeLeft = current.daysMax - current.elapsed;

        if (timeLeft <= 0.5f) {
            fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, current.from,
                    0.5f, "preparing to depart",
                    goNextScript(current));
            return;
        }

        // Try to find and intercept a target subfaction fleet in this system
        CampaignFleetAPI target = findTargetFleet();
        if (target != null) {
            float interceptDays = Math.min(8f, timeLeft);
            fleet.addAssignment(FleetAssignment.INTERCEPT, target, interceptDays,
                    "hunting " + target.getName());
            return;
        }

        // No target found — patrol the system aggressively while waiting
        float patrolDays = Math.min(5f, timeLeft);
        fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, null, patrolDays,
                "searching for targets");
    }

    private CampaignFleetAPI findTargetFleet() {
        if (fleet.getContainingLocation() == null) return null;
        if (!(fleet.getContainingLocation() instanceof StarSystemAPI)) return null;

        CampaignFleetAPI best = null;
        float bestDist = Float.MAX_VALUE;

        for (CampaignFleetAPI other : fleet.getContainingLocation().getFleets()) {
            if (other == fleet) continue;
            if (!targetSubfactionId.equals(other.getFaction().getId())) continue;
            if (other.isEmpty()) continue;

            float dist = com.fs.starfarer.api.util.Misc.getDistance(
                    fleet.getLocation(), other.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = other;
            }
        }

        return best;
    }
}
