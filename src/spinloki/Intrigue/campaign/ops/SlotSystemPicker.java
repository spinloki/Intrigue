package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;

import java.io.Serializable;
import java.util.logging.Logger;

/**
 * A SystemPicker that returns a pre-determined system from a claimed base slot.
 * Used by EstablishTerritoryBaseOp to direct the base creation to the slot's system.
 */
public class SlotSystemPicker implements SystemPicker, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(SlotSystemPicker.class.getName());

    private final String systemId;

    public SlotSystemPicker(String systemId) {
        this.systemId = systemId;
    }

    @Override
    public StarSystemAPI pick() {
        if (systemId == null) {
            log.warning("SlotSystemPicker: no systemId configured.");
            return null;
        }
        StarSystemAPI sys = Global.getSector().getStarSystem(systemId);
        if (sys == null) {
            log.warning("SlotSystemPicker: system '" + systemId + "' not found.");
        } else {
            log.info("SlotSystemPicker: returning pre-assigned system '" + sys.getName() + "'.");
        }
        return sys;
    }
}

