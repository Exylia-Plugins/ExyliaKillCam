package net.exylia.exyliaKillCam;

import net.exylia.exyliaKillCam.bootstrap.ExyliaLibMissing;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit entry point, kept free of ExyliaLib types so it still runs when ExyliaLib is missing.
 *
 * <p>Without ExyliaLib it says where to get the library and disables itself until the restart; with it, it
 * hands over to {@link ExyliaKillCam}.
 */
public final class ExyliaKillCamPlugin extends JavaPlugin {

    private ExyliaKillCam core;

    @Override
    public void onEnable() {
        Plugin lib = getServer().getPluginManager().getPlugin("ExyliaLib");
        if (lib == null || !lib.isEnabled()) {
            ExyliaLibMissing.report(this);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = new ExyliaKillCam();
        core.start(this);
    }

    @Override
    public void onDisable() {
        if (core != null) core.shutdown();
        core = null;
    }
}
