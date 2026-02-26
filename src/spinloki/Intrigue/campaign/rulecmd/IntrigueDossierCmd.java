package spinloki.Intrigue.campaign.rulecmd;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntriguePeopleManager;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.*;

public class IntrigueDossierCmd extends BaseCommandPlugin {

    public static final String OPT_DOSSIER = "intrigue_dossier";
    public static final String OPT_BACK = "intrigue_dossier_back";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {

        if (dialog == null || dialog.getInteractionTarget() == null) return false;

        PersonAPI person = dialog.getInteractionTarget().getActivePerson();
        if (person == null) return false;

        // Only for your special people
        if (!person.hasTag(IntrigueIds.PERSON_TAG)) return false;

        String mode = params != null && !params.isEmpty() ? params.get(0).getString(memoryMap) : "addOption";

        if ("isIntriguePerson".equalsIgnoreCase(mode)){
            return true;
        }

        if ("addOption".equalsIgnoreCase(mode)) {
            // Add an option to view the dossier (safe even if called multiple times)
            if (!dialog.getOptionPanel().hasOption(OPT_DOSSIER)) {
                dialog.getOptionPanel().addOption("Intrigue: Dossier", OPT_DOSSIER);
                dialog.getOptionPanel().setTooltip(OPT_DOSSIER, "View your notes about this person.");
            }
            return true;
        }

        if ("show".equalsIgnoreCase(mode)) {
            IntriguePerson ip = IntriguePeopleManager.get().getById(person.getId());
            if (ip == null) return false;

            dialog.getTextPanel().addPara("— Intrigue Dossier —");
            dialog.getTextPanel().addPara("Role: %s", Misc.getHighlightColor(), ip.getRole().name());

            // Show subfaction info
            if (ip.getSubfactionId() != null && IntrigueServices.isInitialized()) {
                IntrigueSubfaction sf = IntrigueServices.subfactions().getById(ip.getSubfactionId());
                if (sf != null) {
                    dialog.getTextPanel().addPara("Subfaction: %s", Misc.getHighlightColor(), sf.getName());
                    dialog.getTextPanel().addPara("Subfaction Power: %s", Misc.getHighlightColor(), String.valueOf(sf.getPower()));
                }
            }

            if (ip.getBonus() != null) {
                dialog.getTextPanel().addPara("Bonus: %s", Misc.getHighlightColor(), ip.getBonus());
            }

            dialog.getTextPanel().addPara("Relationship to you: %s", Misc.getHighlightColor(), String.valueOf(ip.getRelToPlayer()));

            String traits = ip.getTraits().isEmpty() ? "(none)" : String.join(", ", ip.getTraits());
            dialog.getTextPanel().addPara("Traits: %s", Misc.getHighlightColor(), traits);

            String homeMarketId = ip.getHomeMarketId();
            dialog.getTextPanel().addPara("Home Market Id: %s", Misc.getHighlightColor(), homeMarketId);

            // Top 5 strongest relationships (by absolute value)
            List<Map.Entry<String, Integer>> rels = new ArrayList<>(ip.getRelToOthersView().entrySet());
            rels.sort((a, b) -> Integer.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));

            int shown = 0;
            for (Map.Entry<String, Integer> e : rels) {
                if (shown++ >= 5) break;
                PersonAPI other = Global.getSector().getImportantPeople().getPerson(e.getKey());
                String otherName = other != null ? other.getNameString() : e.getKey();
                dialog.getTextPanel().addPara("Relation to %s: %s", Misc.getHighlightColor(), otherName, "" + e.getValue());
                dialog.getTextPanel().setHighlightColorsInLastPara(
                        Misc.getHighlightColor(),  // for otherName
                        Misc.getNegativeHighlightColor() // for value, for example
                );
            }

            return true;
        }

        return false;
    }
}