package spinloki.Intrigue.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.logging.Logger;

/**
 * Proximity-based hostile sound script attached to a fleet.
 *
 * <p>When the player fleet is in the same location as the owning fleet,
 * this script plays a looping sound whose volume scales with distance,
 * using the same pattern as Underworld's Palace fleet.</p>
 *
 * <p>Sound files are custom mono {@code .ogg} files placed in
 * {@code sounds/intrigue/} and registered as top-level SFX entries in
 * the mod's {@code sounds.json}.</p>
 *
 * <p>This script is <b>not</b> serializable — it's transient and
 * re-attached when fleets are spawned by the route manager.</p>
 */
public class HostileProximityMusicScript implements EveryFrameScript {

    private static final Logger log = Logger.getLogger(HostileProximityMusicScript.class.getName());

    private static final float EXTRA_NORMAL_SPACE_RANGE = 750f;
    private static final float INNER_FALLOFF_CAP = 375f;
    private static final float DIALOG_VOLUME_MULT = 0.01f;

    private final CampaignFleetAPI fleet;
    private final String soundId;
    private final Object loopHandle = new Object();

    private boolean done = false;
    private boolean soundMissing = false;

    /**
     * @param fleet   the fleet this script is attached to
     * @param soundId the SFX sound ID to loop (e.g. {@code "intrigue_hostile_hegemony"})
     */
    public HostileProximityMusicScript(CampaignFleetAPI fleet, String soundId) {
        this.fleet = fleet;
        this.soundId = soundId;
        log.info("HostileProximityMusicScript attached to " + fleet.getName()
                + " with sound " + soundId);
    }

    @Override
    public boolean isDone() { return done; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        if (fleet == null || !fleet.isAlive()) {
            done = true;
            return;
        }
        if (soundMissing) return;

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return;

        LocationAPI fleetLoc = fleet.getContainingLocation();
        LocationAPI playerLoc = playerFleet.getContainingLocation();
        if (fleetLoc == null || playerLoc == null || fleetLoc != playerLoc) return;

        float dist = Misc.getDistance(fleet, playerFleet);
        float falloff2 = playerFleet.getMaxSensorRangeToDetect(fleet)
                + fleet.getRadius() + playerFleet.getRadius();
        falloff2 = Math.min(INNER_FALLOFF_CAP, falloff2);
        float falloff1 = INNER_FALLOFF_CAP + (1.5f * falloff2);
        if (!fleetLoc.isHyperspace()) {
            falloff1 += EXTRA_NORMAL_SPACE_RANGE;
        }

        float vol = 1f - (dist / falloff1);
        if (vol <= 0f) return;

        Vector2f loc = Global.getSoundPlayer().getListenerPos();
        if (loc == null) {
            loc = playerFleet.getLocation();
        }

        if (Global.getSector().getCampaignUI().isShowingDialog()
                || Global.getSector().getCampaignUI().isShowingMenu()
                || Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null) {
            vol *= DIALOG_VOLUME_MULT;
        }

        try {
            Global.getSoundPlayer().playLoop(soundId, loopHandle, 1f, vol, loc, Misc.ZERO);
        } catch (Exception e) {
            log.warning("HostileProximityMusicScript: sound '" + soundId
                    + "' failed — disabling. Drop the .ogg into sounds/intrigue/");
            soundMissing = true;
        }
    }

    /**
     * Resolves the SFX sound ID for a faction's hostile proximity loop.
     * These correspond to {@code .ogg} files in {@code sounds/intrigue/}
     * registered in the mod's {@code sounds.json}.
     *
     * @param factionId the base game faction ID (e.g. "hegemony", "pirates")
     * @return a sound ID for {@code playLoop}
     */
    public static String getHostileMusicIdForFaction(String factionId) {
        if (factionId == null) return "intrigue_hostile_default";
        switch (factionId) {
            case "hegemony":
                return "intrigue_hostile_hegemony";
            case "tritachyon":
                return "intrigue_hostile_tritachyon";
            case "luddic_church":
            case "luddic_path":
                return "intrigue_hostile_luddite";
            case "pirates":
                return "intrigue_hostile_pirates";
            case "persean":
                return "intrigue_hostile_persean";
            case "sindrian_diktat":
                return "intrigue_hostile_diktat";
            default:
                return "intrigue_hostile_default";
        }
    }
}


