package spinloki.Intrigue.campaign.intel;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.IntrigueTerritoryManager;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.awt.Color;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Intel item for a territory. Displays the territory's name, interested factions,
 * and which subfactions are currently present. Highlights all star systems in the
 * territory on the sector map.
 */
public class IntrigueTerritoryIntel extends BaseIntelPlugin implements Serializable {

    private final String territoryId;
    private final String territoryName;
    private final List<String> constellationNames;

    // Cached at creation for persistence across saves
    private final List<String> interestedFactionIds;

    // Transient reference to the live territory; re-acquired on load
    private transient IntrigueTerritory territory;

    public IntrigueTerritoryIntel(IntrigueTerritory territory) {
        this.territory = territory;
        this.territoryId = territory.getTerritoryId();
        this.territoryName = territory.getName();
        this.constellationNames = new ArrayList<>(territory.getConstellationNames());
        this.interestedFactionIds = new ArrayList<>(territory.getInterestedFactions());

        setNew(true);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public String getTerritoryId() {
        return territoryId;
    }

    // ── Display ─────────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return "Territory: " + territoryName;
    }

    @Override
    public String getIcon() {
        return "graphics/icons/intel/colony_autonomous.png";
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float opad = 10f;
        Color h = Misc.getHighlightColor();
        Color tc = Misc.getTextColor();
        Color gray = Misc.getGrayColor();

        // Header with faction color if available
        FactionAPI faction = getFactionForUIColors();
        if (faction != null) {
            info.addSectionHeading("Territory: " + territoryName, faction.getBaseUIColor(),
                    faction.getDarkUIColor(), com.fs.starfarer.api.ui.Alignment.MID, opad);
        } else {
            info.addSectionHeading("Territory: " + territoryName, gray, gray,
                    com.fs.starfarer.api.ui.Alignment.MID, opad);
        }

        // Territory tier (if we can resolve it)
        ensureTerritoryRef();
        if (territory != null && territory.getTier() != null) {
            info.addPara("Tier: %s", opad, tc, h, territory.getTier().toString());
        }

        // Interested factions
        if (!interestedFactionIds.isEmpty()) {
            info.addPara("Interested Factions:", opad, tc, gray);
            for (String factionId : interestedFactionIds) {
                FactionAPI f = Global.getSector().getFaction(factionId);
                if (f != null) {
                    // Display faction name with its faction color and proper capitalization
                    String name = Misc.ucFirst(f.getDisplayName());
                    info.addPara("  • %s", 3f, f.getBaseUIColor(), name);
                } else {
                    info.addPara("  • " + factionId, opad, gray, gray);
                }
            }
        }

        // Subfactions with presence
        ensureTerritoryRef();
        if (territory != null) {
            Collection<String> subfactionsWithPresence = new ArrayList<>();
            for (IntrigueSubfaction sf : IntrigueServices.subfactions().getAll()) {
                if (territory.hasPresence(sf.getSubfactionId())) {
                    subfactionsWithPresence.add(sf.getName() + " (" + territory.getCohesion(sf.getSubfactionId()) + ")");
                }
            }

            if (!subfactionsWithPresence.isEmpty()) {
                info.addPara("Subfactions Present:", opad, tc, gray);
                for (String sfInfo : subfactionsWithPresence) {
                    info.addPara("  • " + sfInfo, opad, Misc.getGrayColor(), gray);
                }
            } else {
                info.addPara("No subfactions currently present in this territory.", opad, gray, gray);
            }
        }

        // Star systems
        if (!constellationNames.isEmpty()) {
            info.addPara("Constellations:", opad, tc, gray);
            for (String cName : constellationNames) {
                info.addPara("  • " + cName, opad, gray, gray);
            }
        }

        // Delete button
        addDeleteButton(info, width);
    }

    @Override
    protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode, boolean isUpdate,
                                   Color tc, float initPad) {
        info.addPara(territoryName, tc, initPad);
        if (!interestedFactionIds.isEmpty()) {
            info.addPara(interestedFactionIds.size() + " interested faction(s)", Misc.getGrayColor(), initPad);
        }
    }

    // ── Faction colors ──────────────────────────────────────────────────

    @Override
    public FactionAPI getFactionForUIColors() {
        if (interestedFactionIds.isEmpty()) {
            return Global.getSector().getPlayerFaction();
        }
        // Use the first interested faction's colors
        FactionAPI faction = Global.getSector().getFaction(interestedFactionIds.get(0));
        return faction != null ? faction : Global.getSector().getPlayerFaction();
    }

    // ── Intel tags ──────────────────────────────────────────────────────

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(IntrigueIds.INTEL_TAG_INTRIGUE);
        tags.add(IntrigueIds.INTEL_TAG_INTRIGUE_TERRITORIES);
        return tags;
    }

    // ── Map highlighting ────────────────────────────────────────────────

    /**
     * Get all star systems that are part of this territory's constellations.
     * Used for highlighting on the sector map.
     */
    private List<StarSystemAPI> getTerritorySystems() {
        List<StarSystemAPI> systems = new ArrayList<>();
        Set<StarSystemAPI> seen = new HashSet<>();

        for (String cName : constellationNames) {
            Constellation c = IntrigueTerritoryManager.resolveConstellationByName(cName);
            if (c == null) continue;

            for (StarSystemAPI sys : c.getSystems()) {
                if (sys != null && !seen.contains(sys)) {
                    systems.add(sys);
                    seen.add(sys);
                }
            }
        }

        return systems;
    }

    /**
     * Get all highlighted systems in this territory for the sector map.
     */
    public Set<StarSystemAPI> getHighlightedSystems() {
        if (isEnding() || isEnded()) return null;
        Set<StarSystemAPI> systems = new HashSet<>(getTerritorySystems());
        return systems.isEmpty() ? null : systems;
    }

    /**
     * Return the center entity of the first system in the territory as the map location.
     */
    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        List<StarSystemAPI> systems = getTerritorySystems();
        if (!systems.isEmpty() && systems.get(0).getCenter() != null) {
            return systems.get(0).getCenter();
        }
        return null;
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private void ensureTerritoryRef() {
        if (territory != null) return;
        // Attempt to re-acquire the territory reference from the manager
        territory = IntrigueTerritoryManager.get().getById(territoryId);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void advanceImpl(float amount) {
        // Nothing to advance; intel persists until manually deleted
    }
}








