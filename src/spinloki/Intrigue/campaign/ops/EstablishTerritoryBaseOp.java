package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: establish a base inside a territory that has already been scouted.
 *
 * Requires presence = SCOUTING in the target territory. Uses a two-phase pipeline:
 * <ol>
 *   <li>{@link EstablishTerritoryBasePhase}: sends a supply fleet escorted by
 *       military fleets to orbit the target system. If the fleet is destroyed, the
 *       op fails. If the fleet is never spawned (abstract), it auto-succeeds.</li>
 *   <li>{@link EstablishBasePhase}: if the escort phase succeeded, creates the
 *       actual station and market in the territory.</li>
 * </ol>
 *
 * On success, advances presence to ESTABLISHED and sets initial territory cohesion.
 * On failure, presence reverts to NONE.
 */
public class EstablishTerritoryBaseOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(EstablishTerritoryBaseOp.class.getName());

    private static final int INITIAL_TERRITORY_COHESION = 50;
    private static final int HOME_COHESION_COST = 8;
    private static final int LEGITIMACY_GAIN = 5;
    private static final float ESTABLISH_DAYS = 45f;

    private final String subfactionId;
    private final EstablishTerritoryBasePhase escortPhase;
    private final EstablishBasePhase basePhase;

    public EstablishTerritoryBaseOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId,
              subfaction.getLeaderId(),
              null,
              subfaction.getSubfactionId(),
              null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);

        // Intel arrow: home market -> territory
        setIntelSourceMarketId(subfaction.getHomeMarketId());
        setIntelDestinationSystemId(IntrigueOpIntel.resolveSystemIdFromTerritory(territoryId));

        // Phase 1: send an escort fleet to the territory
        int escortFP = 20 + (int) (subfaction.getHomeCohesion() * 0.4f);
        int supplyFP = 15 + (int) (subfaction.getHomeCohesion() * 0.2f);

        this.escortPhase = new EstablishTerritoryBasePhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                territoryId,
                escortFP, supplyFP,
                ESTABLISH_DAYS,
                subfaction.getName());
        phases.add(escortPhase);

        // Phase 2: create the actual base (reuses existing base creation logic,
        // constrained to the territory's constellations)
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(territoryId) : null;
        java.util.List<String> constellations = territory != null
                ? territory.getConstellationNames()
                : java.util.Collections.emptyList();
        this.basePhase = new EstablishBasePhase(
                subfaction.getFactionId(), subfaction.getSubfactionId(), subfaction.getName(),
                new TerritorySystemPicker(constellations));
        phases.add(basePhase);
    }

    @Override
    public String getOpTypeName() {
        return "Establish Territory Base";
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        if (leader == null) return;

        log.info("EstablishTerritoryBaseOp started: " + subfactionId
                + " (leader " + leader.getPersonId() + ")"
                + " establishing base in territory " + getTerritoryId());
    }

    @Override
    protected boolean shouldAbort() {
        if (getInitiator() == null) return true;

        // If the escort phase completed but the fleet was destroyed, skip base creation
        if (escortPhase.isDone() && !escortPhase.didSucceed()) return true;

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return true;

        IntrigueTerritory territory = territories.getById(getTerritoryId());
        if (territory == null) return true;

        // Abort if presence was somehow lost (e.g. another op failed and wiped it)
        IntrigueTerritory.Presence presence = territory.getPresence(subfactionId);
        if (presence == IntrigueTerritory.Presence.NONE) {
            log.info("EstablishTerritoryBaseOp " + getOpId()
                    + " aborted: presence lost in " + territory.getName());
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        // If the escort fleet was destroyed, the whole op fails
        if (!escortPhase.didSucceed()) return OpOutcome.FAILURE;
        // Otherwise, check if the base was actually created
        return basePhase.didSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction subfaction = getInitiatorSubfaction();

        if (subfaction != null) {
            subfaction.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        log.info("EstablishTerritoryBaseOp resolved: " + getOpId() + " → " + result
                + " territory=" + getTerritoryId());

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;

        if (result == OpOutcome.SUCCESS && territory != null) {
            // Advance to ESTABLISHED
            territory.setPresence(subfactionId, IntrigueTerritory.Presence.ESTABLISHED);
            territory.setCohesion(subfactionId, INITIAL_TERRITORY_COHESION);

            // Record the base market ID on the territory so friction raids can target it
            String baseMarketId = basePhase.getCreatedMarketId();
            if (baseMarketId != null) {
                territory.setBaseMarketId(subfactionId, baseMarketId);
            }

            if (subfaction != null) {
                // Establishing a base costs home cohesion but builds legitimacy
                subfaction.setHomeCohesion(subfaction.getHomeCohesion() - HOME_COHESION_COST);
                subfaction.setLegitimacy(subfaction.getLegitimacy() + LEGITIMACY_GAIN);
            }

            log.info("  SUCCESS: " + subfactionId + " ESTABLISHED in " + territory.getName()
                    + " (territory cohesion=" + INITIAL_TERRITORY_COHESION + ")");
        } else {
            // Revert presence to NONE - the effort failed
            if (territory != null) {
                territory.setPresence(subfactionId, IntrigueTerritory.Presence.NONE);
            }

            if (subfaction != null) {
                subfaction.setHomeCohesion(subfaction.getHomeCohesion() - HOME_COHESION_COST / 2);
            }

            log.info("  FAILURE: " + subfactionId + " failed to establish base in "
                    + (territory != null ? territory.getName() : getTerritoryId()));
        }

        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }
}

