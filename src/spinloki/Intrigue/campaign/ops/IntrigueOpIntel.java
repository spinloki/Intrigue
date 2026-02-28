package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Generic intel item for Intrigue operations.
 *
 * <p>Wraps an {@link IntrigueOp} to provide an Intel panel entry under the
 * "Intrigue" tab. Shows op type, subfaction names, territory, status, and
 * outcome. Automatically ends when the wrapped op resolves.</p>
 *
 * <p>RaidOp is excluded (its intel comes from {@link IntrigueRaidIntel}).
 * Other ops use this class directly unless they need custom descriptions.</p>
 */
public class IntrigueOpIntel extends BaseIntelPlugin {

    private static final Logger log = Logger.getLogger(IntrigueOpIntel.class.getName());

    /** Placeholder relation threshold for revealing intel to the player. */
    private static final float REVEAL_RELATION_THRESHOLD = -0.9f;

    // Persisted fields for rendering even if the op reference is lost
    private final String opId;
    private final String opTypeName;
    private final String initiatorSubfactionId;
    private final String targetSubfactionId;
    private final String territoryId;
    private final String initiatorSubfactionName;
    private final String targetSubfactionName;
    private final String initiatorFactionId;
    private final String territoryName;

    // Arrow source/destination - persisted for map display
    private final String sourceMarketId;
    private final String destinationMarketId;
    private final String destinationSystemId;

    /** Human-readable destination name for display (e.g. "Alpha Centauri" or "Eventide"). */
    private final String destinationDisplayName;

    // Transient - re-acquired from IntrigueOpsManager on load
    private transient IntrigueOp op;

    // Cached final status for after the op is cleaned up
    private String cachedOutcome;
    private String cachedFinalStatus;

    public IntrigueOpIntel(IntrigueOp op) {
        this.op = op;
        this.opId = op.getOpId();
        this.opTypeName = op.getOpTypeName();
        this.initiatorSubfactionId = op.getInitiatorSubfactionId();
        this.targetSubfactionId = op.getTargetSubfactionId();
        this.territoryId = op.getTerritoryId();
        this.initiatorFactionId = resolveFactionId(initiatorSubfactionId);

        // Cache display names at creation time
        IntrigueSubfaction initSf = IntrigueServices.subfactions().getById(initiatorSubfactionId);
        this.initiatorSubfactionName = initSf != null ? initSf.getName() : initiatorSubfactionId;

        IntrigueSubfaction targetSf = targetSubfactionId != null
                ? IntrigueServices.subfactions().getById(targetSubfactionId) : null;
        this.targetSubfactionName = targetSf != null ? targetSf.getName() : targetSubfactionId;

        IntrigueTerritory terr = territoryId != null && IntrigueServices.territories() != null
                ? IntrigueServices.territories().getById(territoryId) : null;
        this.territoryName = terr != null ? terr.getName() : territoryId;

        // Arrow display data
        this.sourceMarketId = op.getIntelSourceMarketId();
        this.destinationMarketId = op.getIntelDestinationMarketId();
        this.destinationSystemId = op.getIntelDestinationSystemId();
        this.destinationDisplayName = resolveDestinationDisplayName();

        setNew(true);
    }

    // ── Visibility condition ────────────────────────────────────────────

    /**
     * Placeholder condition for revealing intel to the player. Returns true if
     * the player's relation with the initiator's parent faction is at least -90.
     * This is almost always true - intended to be replaced with more nuanced
     * conditions later (contacts, spy networks, etc.).
     */
    public static boolean shouldRevealToPlayer(IntrigueOp op) {
        if (!PhaseUtil.isSectorAvailable()) return false;

        String factionId = resolveFactionId(op.getInitiatorSubfactionId());
        if (factionId == null) return true; // Can't determine - default to visible

        FactionAPI playerFaction = Global.getSector().getPlayerFaction();
        float rel = playerFaction.getRelationship(factionId);
        return rel >= REVEAL_RELATION_THRESHOLD;
    }

    // ── Display ─────────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return opTypeName + " - " + initiatorSubfactionName;
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float opad = 10f;
        Color h = Misc.getHighlightColor();
        Color tc = Misc.getTextColor();
        Color gray = Misc.getGrayColor();

        // Header: faction color bar
        FactionAPI faction = getFactionForUIColors();
        if (faction != null) {
            info.addSectionHeading(opTypeName, faction.getBaseUIColor(),
                    faction.getDarkUIColor(), com.fs.starfarer.api.ui.Alignment.MID, opad);
        }

        // Initiator
        info.addPara("Initiated by: %s", opad, tc, h, initiatorSubfactionName);

        // Target (if any)
        if (targetSubfactionName != null) {
            info.addPara("Target: %s", opad, tc, h, targetSubfactionName);
        }

        // Territory (if any)
        if (territoryName != null) {
            info.addPara("Territory: %s", opad, tc, h, territoryName);
        }

        // Destination system (if any)
        if (destinationDisplayName != null) {
            info.addPara("Destination: %s", opad, tc, h, destinationDisplayName);
        }

        // Status
        ensureOpRef();
        if (op != null && !op.isResolved()) {
            String status = op.getStatusText();
            info.addPara("Status: %s", opad, tc, h, status);
        } else {
            // Op resolved or reference lost
            String outcomeStr = cachedOutcome != null ? cachedOutcome : "Unknown";
            String statusStr = cachedFinalStatus != null ? cachedFinalStatus : "Resolved";
            Color outcomeColor = "SUCCESS".equals(outcomeStr) ? Misc.getPositiveHighlightColor()
                    : "FAILURE".equals(outcomeStr) ? Misc.getNegativeHighlightColor() : gray;
            info.addPara("Outcome: %s", opad, tc, outcomeColor, outcomeStr);
            info.addPara("Status: %s", opad, tc, gray, statusStr);
        }

        // Mischief sabotage indicator
        ensureOpRef();
        if (op != null && op.getMischiefPenalty() > 0f) {
            info.addPara("Being sabotaged! Success chance reduced by %s.",
                    opad, Misc.getNegativeHighlightColor(),
                    String.format("%.0f%%", op.getMischiefPenalty() * 100));
        }

        // Delete button
        addDeleteButton(info, width);
    }

    @Override
    protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode, boolean isUpdate,
                                   Color tc, float initPad) {
        ensureOpRef();
        if (op != null && !op.isResolved()) {
            String status = op.getStatusText();
            info.addPara(status, tc, initPad);
        } else if (cachedOutcome != null) {
            Color c = "SUCCESS".equals(cachedOutcome) ? Misc.getPositiveHighlightColor()
                    : Misc.getNegativeHighlightColor();
            info.addPara("Outcome: " + cachedOutcome, c, initPad);
        }

        if (territoryName != null) {
            info.addPara("Territory: " + territoryName, Misc.getGrayColor(), initPad);
        }

        if (destinationDisplayName != null) {
            info.addPara("Destination: " + destinationDisplayName, Misc.getGrayColor(), initPad);
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void advanceImpl(float amount) {
        ensureOpRef();

        if (op != null && op.isResolved()) {
            cacheOutcome();
            endAfterDelay();
        } else if (op == null && !isEnding() && !isEnded()) {
            // Op reference lost (save/load or already cleaned up) - end gracefully
            endAfterDelay();
        }
    }

    /**
     * Notify this intel that its op has resolved. Called from IntrigueOp.resolve().
     */
    public void notifyOpResolved() {
        cacheOutcome();
        endAfterDelay();
    }

    private void cacheOutcome() {
        if (cachedOutcome != null) return; // Already cached
        if (op != null) {
            cachedOutcome = op.getOutcome() != null ? op.getOutcome().name() : "UNKNOWN";
            cachedFinalStatus = op.getStatusText();
            if (op.wasSabotagedByMischief()) {
                cachedFinalStatus += " (sabotaged by mischief)";
            }
        }
    }

    // ── Faction colors ──────────────────────────────────────────────────

    @Override
    public FactionAPI getFactionForUIColors() {
        if (!PhaseUtil.isSectorAvailable()) return null;
        if (initiatorFactionId != null) {
            return Global.getSector().getFaction(initiatorFactionId);
        }
        return Global.getSector().getPlayerFaction();
    }

    // ── Tags ────────────────────────────────────────────────────────────

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(IntrigueIds.INTEL_TAG_INTRIGUE);
        return tags;
    }

    // ── Map location and arrows ────────────────────────────────────────

    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        if (!PhaseUtil.isSectorAvailable()) return null;

        // Prefer the explicit source market
        if (sourceMarketId != null) {
            MarketAPI market = Global.getSector().getEconomy().getMarket(sourceMarketId);
            if (market != null && market.getPrimaryEntity() != null) {
                return market.getPrimaryEntity();
            }
        }

        // Fall back to initiator's home market
        if (initiatorSubfactionId != null) {
            IntrigueSubfaction sf = IntrigueServices.subfactions().getById(initiatorSubfactionId);
            if (sf != null && sf.getHomeMarketId() != null) {
                MarketAPI market = Global.getSector().getEconomy().getMarket(sf.getHomeMarketId());
                if (market != null) return market.getPrimaryEntity();
            }
        }
        return null;
    }

    @Override
    public List<IntelInfoPlugin.ArrowData> getArrowData(SectorMapAPI map) {
        if (!PhaseUtil.isSectorAvailable()) return null;
        if (isEnding() || isEnded()) return null;

        SectorEntityToken from = resolveSourceEntity(map);
        SectorEntityToken to = resolveDestinationEntity();

        if (from == null || to == null) return null;
        // Don't draw arrow if source and destination are in the same system
        if (from.getContainingLocation() == to.getContainingLocation()) return null;

        List<IntelInfoPlugin.ArrowData> result = new ArrayList<>();
        FactionAPI faction = getFactionForUIColors();
        IntelInfoPlugin.ArrowData arrow = new IntelInfoPlugin.ArrowData(from, to);
        if (faction != null) {
            arrow.color = faction.getBaseUIColor();
        }
        result.add(arrow);
        return result;
    }

    private SectorEntityToken resolveSourceEntity(SectorMapAPI map) {
        // Use the intel icon entity if available (shown on sector map)
        if (map != null) {
            SectorEntityToken iconEntity = map.getIntelIconEntity(this);
            if (iconEntity != null) return iconEntity;
        }
        // Fall back to the source market entity
        if (sourceMarketId != null) {
            MarketAPI market = Global.getSector().getEconomy().getMarket(sourceMarketId);
            if (market != null && market.getPrimaryEntity() != null) {
                return market.getPrimaryEntity();
            }
        }
        return getMapLocation(map);
    }

    private SectorEntityToken resolveDestinationEntity() {
        // Destination market takes priority
        if (destinationMarketId != null) {
            MarketAPI market = Global.getSector().getEconomy().getMarket(destinationMarketId);
            if (market != null && market.getPrimaryEntity() != null) {
                return market.getPrimaryEntity();
            }
        }
        // Then destination system - try getStarSystem first, fall back to iteration
        if (destinationSystemId != null) {
            StarSystemAPI system = Global.getSector().getStarSystem(destinationSystemId);
            if (system != null) return system.getCenter();

            // Fallback: iterate all systems and match by name
            for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                if (destinationSystemId.equals(sys.getName())
                        || destinationSystemId.equals(sys.getBaseName())) {
                    return sys.getCenter();
                }
            }
            log.warning("IntrigueOpIntel: could not resolve destination system '" + destinationSystemId + "'");
        }
        return null;
    }

    /**
     * Build a human-readable destination name at construction time.
     * Shows the system name for system destinations, market name for market destinations.
     */
    private String resolveDestinationDisplayName() {
        if (!PhaseUtil.isSectorAvailable()) return null;

        // Market destination
        if (destinationMarketId != null) {
            MarketAPI market = Global.getSector().getEconomy().getMarket(destinationMarketId);
            if (market != null) {
                String name = market.getName();
                if (market.getStarSystem() != null) {
                    name += " (" + market.getStarSystem().getBaseName() + ")";
                }
                return name;
            }
        }
        // System destination
        if (destinationSystemId != null) {
            // Try exact match first
            StarSystemAPI system = Global.getSector().getStarSystem(destinationSystemId);
            if (system != null) return system.getBaseName();

            // Fallback iteration
            for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                if (destinationSystemId.equals(sys.getName())
                        || destinationSystemId.equals(sys.getBaseName())) {
                    return sys.getBaseName();
                }
            }
            // Last resort: just return the raw ID
            return destinationSystemId;
        }
        return null;
    }

    // ── Sorting ─────────────────────────────────────────────────────────

    @Override
    public IntelSortTier getSortTier() {
        if (isEnding() || isEnded()) return IntelSortTier.TIER_COMPLETED;
        return IntelSortTier.TIER_3;
    }

    @Override
    public String getSortString() {
        return getName();
    }

    // ── Save/load re-acquisition ────────────────────────────────────────

    private void ensureOpRef() {
        if (op != null) return;
        if (!PhaseUtil.isSectorAvailable()) return;

        // Try to find the op in the active ops list
        for (IntrigueOp active : IntrigueServices.ops().getActiveOps()) {
            if (opId.equals(active.getOpId())) {
                op = active;
                return;
            }
        }
        // Op no longer active - it resolved while we were unloaded
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private static String resolveFactionId(String subfactionId) {
        if (subfactionId == null) return null;
        IntrigueSubfaction sf = IntrigueServices.subfactions() != null
                ? IntrigueServices.subfactions().getById(subfactionId) : null;
        return sf != null ? sf.getFactionId() : null;
    }

    /**
     * Resolve a star system name from a territory by finding the first system
     * in one of its constellations. Used for arrow display when the op targets
     * a territory rather than a specific market/system.
     * Returns null if no system can be found or the sector is not available.
     */
    public static String resolveSystemIdFromTerritory(String territoryId) {
        if (territoryId == null) return null;
        if (!PhaseUtil.isSectorAvailable()) return null;

        IntrigueTerritory terr = IntrigueServices.territories() != null
                ? IntrigueServices.territories().getById(territoryId) : null;
        if (terr == null) {
            log.info("resolveSystemIdFromTerritory: territory not found: " + territoryId);
            return null;
        }

        List<String> constellationNames = terr.getConstellationNames();
        if (constellationNames.isEmpty()) {
            log.info("resolveSystemIdFromTerritory: territory '" + terr.getName()
                    + "' has no constellations assigned yet.");
            return null;
        }

        for (String constellationName : constellationNames) {
            for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                if (sys.getConstellation() != null
                        && constellationName.equals(sys.getConstellation().getName())) {
                    log.info("resolveSystemIdFromTerritory: resolved '" + terr.getName()
                            + "' -> system '" + sys.getName() + "'");
                    return sys.getName();
                }
            }
        }
        log.warning("resolveSystemIdFromTerritory: no matching systems for territory '"
                + terr.getName() + "' constellations: " + constellationNames);
        return null;
    }

    public String getOpId() {
        return opId;
    }
}


