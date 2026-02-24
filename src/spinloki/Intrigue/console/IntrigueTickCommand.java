package spinloki.Intrigue.console;

import com.fs.starfarer.api.Global;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntriguePacerScript;

import java.util.Map;

public class IntrigueTickCommand implements BaseCommand {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!IntrigueCommandUtil.isCampaignContext(context)) return CommandResult.WRONG_CONTEXT;

        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(IntrigueIds.PERSIST_PACER_SCRIPT_KEY);

        IntriguePacerScript script;
        if (existing instanceof IntriguePacerScript) {
            script = (IntriguePacerScript) existing;
        } else {
            script = new IntriguePacerScript();
            Global.getSector().addScript(script);
            data.put(IntrigueIds.PERSIST_PACER_SCRIPT_KEY, script);
        }

        String result = script.forceTick();
        if (result != null && !result.isEmpty()) {
            Console.showMessage(result);
        } else {
            Console.showMessage("Pacer tick executed.");
        }
        return CommandResult.SUCCESS;
    }
}