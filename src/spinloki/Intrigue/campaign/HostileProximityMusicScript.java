package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.logging.Logger;

/**
 * Proximity-based hostile music script attached to a fleet.
 *
 * <p>When the player fleet is in the same location (system/hyperspace) as
 * the owning fleet, this script plays a looping sound whose volume scales
 * smoothly with distance. The sound fades in as the player approaches and
 * fades out as they depart, creating an atmospheric audio cue that a
 * hostile faction is active nearby.</p>
 *
 * <p>This follows the same proximity-music pattern used by the Underworld
 * mod's Palace fleet (see {@code PROXIMITY_MUSIC_SYSTEM.md}):</p>
 * <ul>
 *   <li>Two falloff ranges calculated from sensor range + fleet radii</li>
 *   <li>Volume reduced to near-zero when UI/dialogs are open</li>
 *   <li>Uses {@code Global.getSoundPlayer().playLoop()} for smooth looping</li>
 * </ul>
 *
 * <p>The sound ID should correspond to a faction's hostile encounter music
 * registered in {@code data/config/sounds.json}. For vanilla factions, the
 * convention is {@code "music_encounter_hostile_<factionId>"}.</p>
 *
 * <p>This script is <b>not</b> serializable — it's transient and re-attached
 * when fleets are spawned by the route manager. It removes itself
 * automatically when the owning fleet is no longer alive.</p>
 */
public class HostileProximityMusicScript implements EveryFrameScript {

    private static final Logger log = Logger.getLogger(HostileProximityMusicScript.class.getName());

    /** Maximum range (in units) at which the outer sound layer begins to fade in. */
    private static final float EXTRA_NORMAL_SPACE_RANGE = 3000f;
    /** Hard cap on the inner falloff range. */
    private static final float INNER_FALLOFF_CAP = 1500f;
    /** Volume multiplier when a UI dialog or menu is open. */
    private static final float DIALOG_VOLUME_MULT = 0.01f;

    private final CampaignFleetAPI fleet;
    private final String soundId;

    /** Stable object reference for the playLoop API to track the sound instance. */
    private final Object loopHandle = new Object();

    private boolean done = false;

    /**
     * @param fleet   the fleet this script is attached to
     * @param soundId the sound ID to loop (e.g. {@code "music_encounter_hostile_pirates"})
     */
    public HostileProximityMusicScript(CampaignFleetAPI fleet, String soundId) {
        this.fleet = fleet;
        this.soundId = soundId;
        log.info("HostileProximityMusicScript attached to " + fleet.getName()
                + " with sound " + soundId);
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    @Override
    public void advance(float amount) {
        // Self-cleanup if fleet is gone
        if (fleet == null || !fleet.isAlive()) {
            done = true;
            return;
        }

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return;

        // Must be in the same location (star system or hyperspace)
        LocationAPI fleetLoc = fleet.getContainingLocation();
        LocationAPI playerLoc = playerFleet.getContainingLocation();
        if (fleetLoc == null || playerLoc == null || fleetLoc != playerLoc) return;

        // Calculate distance
        Vector2f fleetPos = fleet.getLocation();
        Vector2f playerPos = playerFleet.getLocation();
        float distance = Misc.getDistance(fleetPos, playerPos);

        // Calculate falloff ranges
        float sensorRange = playerFleet.getMaxSensorRangeToDetect(fleet);
        float fleetRadius = fleet.getRadius() + playerFleet.getRadius();
        float innerFalloff = Math.min(sensorRange + fleetRadius, INNER_FALLOFF_CAP);
        float outerFalloff = INNER_FALLOFF_CAP + (1.5f * innerFalloff);

        // Extra range in normal space (not hyperspace)
        if (!fleetLoc.isHyperspace()) {
            outerFalloff += EXTRA_NORMAL_SPACE_RANGE;
        }

        // Calculate volume (0–1) based on distance
        float volume = 1f - (distance / outerFalloff);
        if (volume <= 0f) return;
        volume = Math.min(1f, volume);

        // Suppress volume during dialogs/menus
        if (Global.getSector().getCampaignUI().isShowingDialog()
                || Global.getSector().getCampaignUI().isShowingMenu()
                || Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null) {
            volume *= DIALOG_VOLUME_MULT;
        }

        // Play the looping sound at the player's location so it sounds centered
        Global.getSoundPlayer().playLoop(soundId, loopHandle, 1f, volume, playerPos, Misc.ZERO);
    }

    /**
     * Resolves the appropriate hostile encounter music sound ID for a given
     * vanilla faction. Falls back to a generic hostile encounter sound if
     * the faction doesn't have a specific one.
     *
     * @param factionId the base game faction ID (e.g. "hegemony", "pirates")
     * @return a sound ID suitable for {@code Global.getSoundPlayer().playLoop()}
     */
    public static String getHostileMusicIdForFaction(String factionId) {
        if (factionId == null) return "music_encounter_hostile";
        switch (factionId) {
            case "hegemony":
                return "music_encounter_hostile_hegemony";
            case "tritachyon":
                return "music_encounter_hostile_tritachyon";
            case "luddic_church":
                return "music_encounter_hostile_luddic_church";
            case "luddic_path":
                return "music_encounter_hostile_luddic_path";
            case "pirates":
                return "music_encounter_hostile_pirates";
            case "persean":
                return "music_encounter_hostile_persean";
            case "sindrian_diktat":
                return "music_encounter_hostile_sindrian_diktat";
            case "independent":
                return "music_encounter_hostile_independent";
            default:
                return "music_encounter_hostile";
        }
    }
}



