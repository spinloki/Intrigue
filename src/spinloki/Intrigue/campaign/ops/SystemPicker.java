package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.campaign.StarSystemAPI;

import java.io.Serializable;

/**
 * Strategy interface for choosing a star system in which to establish a base.
 *
 * Different subfaction types will have different preferences:
 * - Criminal subfactions prefer systems near (but not in) the core with few salvageables.
 * - Political subfactions may prefer frontier systems near friendly territory.
 * - Other mods can provide their own implementations.
 *
 * Implementations must be Serializable since they are stored inside op phases.
 */
public interface SystemPicker extends Serializable {

    /**
     * Pick a star system suitable for base establishment.
     *
     * @return a system to place a base in, or null if no suitable system was found
     */
    StarSystemAPI pick();
}


