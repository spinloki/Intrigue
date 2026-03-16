package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Intel for a territory-wide council operation. Emissary fleets from all
 * subfactions in the territory converge on a meeting point in a system where
 * no subfaction bases are present.
 *
 * <p>Fleet size scales with the number of hostile subfaction pairs — more
 * conflict means bigger delegations (heavier escorts). All council fleets
 * are marked non-hostile to each other via memory flags so they don't fight
 * en route even if their factions are at war.</p>
 *
 * <p>On resolution, the entanglement map may be rewritten (done by
 * TerritoryState, not this Intel).</p>
 */
public class TerritoryCouncilIntel extends MultiFleetOpIntel {

    private static final Logger log = Global.getLogger(TerritoryCouncilIntel.class);

    /** Base FP per emissary fleet (modest escort). */
    private static final float BASE_FP = 60f;

    /** Additional FP per hostile pair in the territory. */
    private static final float FP_PER_HOSTILE_PAIR = 20f;

    /** Maximum FP per emissary fleet. */
    private static final float MAX_FP = 150f;

    /** Days spent at the council POI negotiating. */
    private static final float COUNCIL_DAYS = 15f;

    /** Non-hostile flag applied to all council fleets so they don't fight each other. */
    private static final String COUNCIL_NON_HOSTILE_FLAG = "$intrigueCouncilNonHostile";

    private final int participantCount;
    private final int hostilePairCount;
    private final float fleetFP;

    /** Subfaction IDs of all participants (for display and fleet tracking). */
    private final List<String> participantIds = new ArrayList<>();

    /**
     * Data for a single council participant.
     */
    public static class Participant {
        public final SubfactionDef def;
        public final BaseSlot slot;

        public Participant(SubfactionDef def, BaseSlot slot) {
            this.def = def;
            this.slot = slot;
        }
    }

    /**
     * @param op               The COUNCIL ActiveOp.
     * @param triggerDef       The subfaction that triggered the council.
     * @param participants     All subfactions participating (including the trigger).
     * @param meetingSystemId  System ID of the base-free meeting system.
     * @param hostilePairCount Number of hostile subfaction pairs in the territory.
     * @param territoryId      Territory this council operates in.
     */
    public TerritoryCouncilIntel(ActiveOp op, SubfactionDef triggerDef,
                                  List<Participant> participants,
                                  String meetingSystemId,
                                  int hostilePairCount,
                                  String territoryId) {
        super(op, triggerDef, territoryId);
        this.participantCount = participants.size();
        this.hostilePairCount = hostilePairCount;
        this.fleetFP = Math.min(MAX_FP, BASE_FP + FP_PER_HOSTILE_PAIR * hostilePairCount);

        // Find the meeting point — center of the base-free system
        SectorEntityToken meetingPoint = findSystemCenter(meetingSystemId);
        if (meetingPoint == null) {
            log.error("TerritoryCouncilIntel: meeting system not found: " + meetingSystemId);
            endNow();
            return;
        }

        // Create a route for each participating subfaction
        for (Participant p : participants) {
            participantIds.add(p.def.id);

            MarketAPI participantMarket = findBaseMarket(p.slot);
            if (participantMarket == null) {
                log.warn("TerritoryCouncilIntel: no market for " + p.def.name + ", skipping");
                continue;
            }

            SectorEntityToken origin = participantMarket.getPrimaryEntity();
            float travelDays = computeTravelDays(origin, meetingPoint);

            RouteData councilRoute = addManagedRoute(participantMarket, FleetTypes.PATROL_MEDIUM,
                    p.def.id, p.def.id);
            councilRoute.addSegment(new RouteSegment(PREP_DAYS, origin));
            councilRoute.addSegment(new RouteSegment(travelDays, origin, meetingPoint));
            councilRoute.addSegment(new RouteSegment(COUNCIL_DAYS, meetingPoint));
            councilRoute.addSegment(new RouteSegment(travelDays, meetingPoint, origin));
            councilRoute.addSegment(new RouteSegment(END_DAYS, origin));
            allRoutes.add(councilRoute);
        }

        if (allRoutes.isEmpty()) {
            log.error("TerritoryCouncilIntel: no participants could create routes");
            endNow();
            return;
        }

        initMultiFleet(true);

        log.info("TerritoryCouncilIntel: " + participantCount + " subfactions convening " +
                "in " + meetingSystemId + " (hostile pairs: " + hostilePairCount +
                ", fleet FP: " + (int) fleetFP + ")");
    }

    // ── Route / fleet overrides ──────────────────────────────────────────

    @Override
    protected String getFleetType() {
        return FleetTypes.PATROL_MEDIUM;
    }

    @Override
    protected float getBaseCombatFP() {
        return fleetFP;
    }

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        // Each fleet uses its own subfaction for composition and faction
        String fleetFactionId = route.getExtra().factionId;

        float tankerFP = fleetFP * TANKER_FRACTION;
        float freighterFP = fleetFP * FREIGHTER_FRACTION;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                fleetFactionId,
                route.getQualityOverride(),
                FleetTypes.PATROL_MEDIUM,
                fleetFP,
                freighterFP,
                tankerFP,
                0f, 0f, 0f,
                def.fleetQualityMod
        );
        params.timestamp = route.getTimestamp();

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) return null;

        fleet.setFaction(fleetFactionId, true);
        configureFleetMemory(fleet);
        fleet.getEventListeners().add(this);

        market.getContainingLocation().addEntity(fleet);
        fleet.setFacing((float) Math.random() * 360f);
        SectorEntityToken primary = market.getPrimaryEntity();
        fleet.setLocation(primary.getLocation().x, primary.getLocation().y);

        fleet.addScript(new PatrolRouteAI(fleet, route));

        log.info("TerritoryCouncilIntel: spawned emissary fleet (" +
                fleet.getFleetPoints() + " FP) for " + fleetFactionId);

        return fleet;
    }

    @Override
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
        // Council fleets must not fight each other — temporary diplomatic immunity
        fleet.getMemoryWithoutUpdate().set(COUNCIL_NON_HOSTILE_FLAG, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return "Territory Council";
    }

    @Override
    public String getSortString() {
        return "Intrigue Council";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("%s delegations assembling", 3f, Misc.getHighlightColor(),
                String.valueOf(participantCount));
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        info.addPara("A territory-wide council has been called in the " +
                        formatSystemName(territoryId) + " territory. Emissary fleets from " +
                        "%s subfactions are converging to negotiate.",
                pad, Misc.getHighlightColor(), String.valueOf(participantCount));

        if (hostilePairCount > 0) {
            info.addPara("Current hostilities: %s pair(s) — delegations are heavily escorted.",
                    pad, Misc.getNegativeHighlightColor(), String.valueOf(hostilePairCount));
        } else {
            info.addPara("No active hostilities — delegations are lightly escorted.",
                    Misc.getGrayColor(), pad);
        }

        info.addPara("Meeting point: %s", pad, Misc.getHighlightColor(),
                formatSystemName(targetSystemId));

        if (fleetsDestroyed > 0) {
            info.addPara("%s emissary fleet(s) destroyed.", pad,
                    Misc.getNegativeHighlightColor(),
                    String.valueOf(fleetsDestroyed));
        }
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        // Use a neutral color since multiple factions participate
        return Global.getSector().getFaction(subfactionId);
    }

    public List<String> getParticipantIds() {
        return Collections.unmodifiableList(participantIds);
    }
}
