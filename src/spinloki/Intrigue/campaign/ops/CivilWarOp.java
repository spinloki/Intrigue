package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * Consequence op: civil war erupts within a subfaction whose home cohesion
 * has been critically low for too long. Fleets spawn en-masse and fight
 * each other. When it resolves, the subfaction's home cohesion and
 * legitimacy are both reset to 50 — a clean slate after the chaos.
 */
public class CivilWarOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(CivilWarOp.class.getName());

    private final String subfactionId;

    public CivilWarOp(String opId, IntrigueSubfaction subfaction) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        this.subfactionId = subfaction.getSubfactionId();
        // TODO: game-side phase that spawns massive fleet battle at home market
        phases.add(new InfightingOp.TimedPhase("Civil War", 7f));
    }

    @Override public String getOpTypeName() { return "Civil War"; }
    @Override protected void onStarted() {
        log.info("CIVIL WAR erupted within " + subfactionId + "!");
    }

    @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null) {
            sf.setHomeCohesion(50);
            sf.setLegitimacy(50);
            sf.resetLowHomeCohesionTicks();
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            log.info("Civil War resolved: " + subfactionId
                    + " cohesion and legitimacy reset to 50");
        }
    }
}

