package net.exylia.exyliaKillCam.command;

import net.exylia.exyliaKillCam.ExyliaKillCam;
import net.exylia.exyliaKillCam.Permissions;
import net.exylia.exyliaKillCam.config.KillCamMessages;
import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.text.Values;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/** {@code /killcam}: the last death again, the skip button, and what happens after dying. */
@Command({"killcam", "exyliakillcam"})
public class KillCamCommand {

    @CommandPlaceholder
    @CommandPermission(Permissions.REWATCH)
    public void watch(Player player) {
        ExyliaKillCam.get().getKillCams().watchLast(player);
    }

    /** Never gated: getting out of a killcam must always work. */
    @Subcommand("skip")
    public void skip(Player player) {
        ExyliaKillCam.get().getKillCams().skip(player);
    }

    /** {@code AUTO}, {@code ASK} or {@code OFF}; the same choice {@code /settings} shows. */
    @Subcommand("mode")
    @CommandPermission(Permissions.MODE)
    public void mode(Player player, KillCamSettings.Mode mode) {
        ExyliaKillCam core = ExyliaKillCam.get();
        if (!core.getKillCams().mode(player, mode)) return;
        KillCamMessages lines = KillCamMessages.get();
        String name = switch (mode) {
            case AUTO -> lines.modeAuto();
            case ASK -> lines.modeAsk();
            case OFF -> lines.modeOff();
        };
        core.getMessages().send(player, lines.modeSet(), Values.of("mode", name));
    }

    @Subcommand("reload")
    @CommandPermission(Permissions.ADMIN)
    public void reload(CommandSender sender) {
        ExyliaKillCam core = ExyliaKillCam.get();
        core.reload(sender);
        core.getMessages().send(sender, KillCamMessages.get().reloaded());
    }
}
