package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.io.Serializable;
import java.util.logging.Logger;

/**
 * OpPhase that wraps an IntrigueRaidIntel (FleetGroupIntel) lifecycle.
 *
 * On first advance when the sector is available, it creates and registers
 * the IntrigueRaidIntel. The phase is done when the FGI ends.
 *
 * In sim mode (no Global.getSector()), it auto-resolves as a failure.
 */
public class FGIPhase implements OpPhase, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(FGIPhase.class.getName());

    private final String attackerSubfactionId;
    private final String defenderSubfactionId;
    private final int combatFP;

    private transient IntrigueRaidIntel intel;
    private String intelMemoryKey;
    private boolean started = false;
    private boolean done = false;
    private boolean succeeded = false;

    /**
     * @param attackerSubfactionId subfaction launching the raid
     * @param defenderSubfactionId subfaction being raided
     * @param combatFP             total fleet points
     */
    public FGIPhase(String attackerSubfactionId, String defenderSubfactionId, int combatFP) {
        this.attackerSubfactionId = attackerSubfactionId;
        this.defenderSubfactionId = defenderSubfactionId;
        this.combatFP = combatFP;
    }

    @Override
    public void advance(float days) {
        if (done) return;

        // Sim mode: no sector available, auto-resolve
        if (!PhaseUtil.isSectorAvailable()) {
            done = true;
            succeeded = false;
            return;
        }

        if (!started) {
            startIntel();
            return;
        }

        // Re-acquire transient reference after save/load
        if (intel == null && intelMemoryKey != null) {
            intel = (IntrigueRaidIntel) Global.getSector().getMemoryWithoutUpdate().get(intelMemoryKey);
            if (intel == null) {
                // Intel was cleaned up (save/load edge case)
                log.warning("FGIPhase: lost reference to IntrigueRaidIntel, resolving as failure");
                done = true;
                succeeded = false;
                return;
            }
        }

        // Check if the FGI has ended
        if (intel != null && intel.isEnded()) {
            succeeded = intel.raidSucceeded();
            done = true;
            log.info("FGIPhase completed: " + (succeeded ? "SUCCESS" : "FAILURE"));
        }
    }

    private void startIntel() {
        IntrigueSubfaction attacker = IntrigueServices.subfactions().getById(attackerSubfactionId);
        IntrigueSubfaction defender = IntrigueServices.subfactions().getById(defenderSubfactionId);

        if (attacker == null || defender == null) {
            log.warning("FGIPhase: attacker or defender subfaction not found, aborting."
                    + " attacker=" + attackerSubfactionId + " defender=" + defenderSubfactionId);
            done = true;
            succeeded = false;
            return;
        }

        try {
            intel = new IntrigueRaidIntel(attacker, defender, combatFP);

            // Use the key the intel registered itself under
            intelMemoryKey = intel.getMemKey();

            // Register as intel so it shows up in the player's intel screen
            Global.getSector().getIntelManager().addIntel(intel);

            started = true;

            log.info("FGIPhase: started IntrigueRaidIntel for "
                    + attacker.getName() + " → " + defender.getName()
                    + " (key=" + intelMemoryKey + ")");
        } catch (Exception e) {
            log.severe("FGIPhase: failed to create IntrigueRaidIntel: " + e.getMessage());
            e.printStackTrace();
            done = true;
            succeeded = false;
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!started) return "Preparing";
        if (done) return succeeded ? "Raid succeeded" : "Raid failed";
        return "Raid in progress";
    }

    public boolean didRaidSucceed() {
        return succeeded;
    }

    /**
     * Force-abort the FGI (e.g. when the op is cancelled due to faction hostility change).
     */
    public void abort() {
        if (intel != null && !intel.isEnded() && !intel.isEnding()) {
            intel.abort();
        }
        done = true;
        succeeded = false;
    }

}

