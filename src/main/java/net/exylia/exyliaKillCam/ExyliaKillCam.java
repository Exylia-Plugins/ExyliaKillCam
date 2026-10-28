package net.exylia.exyliaKillCam;

import net.exylia.exyliaKillCam.command.KillCamCommand;
import net.exylia.exyliaKillCam.config.KillCamMessages;
import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.exyliaKillCam.killcam.KillCamHotbar;
import net.exylia.exyliaKillCam.killcam.KillCamListener;
import net.exylia.exyliaKillCam.killcam.KillCams;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.config.Configs;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.reload.Reloads;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.settings.Settings;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.PluginMessages;
import net.exylia.lib.text.Prefixes;
import net.exylia.lib.util.teleport.PluginTeleports;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.bukkit.BukkitLamp;

import java.util.Locale;

/**
 * ExyliaKillCam: the seconds before a death, watched again through the
 * killer's eyes, ending on the body and the killer's kill effect.
 *
 * <p>Not the Bukkit plugin itself: {@link ExyliaKillCamPlugin} is, so it can
 * say where to get ExyliaLib when the server lacks it. Every library view is
 * asked for with {@link #getPlugin()}, because the library keys its per-plugin
 * views by Bukkit plugin.
 */
public final class ExyliaKillCam {

    /** The namespace the hotbar's actions are written against. */
    public static final String NAMESPACE = "exyliakillcam";

    private static ExyliaKillCam instance;

    private JavaPlugin plugin;
    private TaskScheduler tasks;
    private PluginMessages messages;
    private PluginTeleports teleports;
    private Debug debug;
    private Reloads reloads;
    private KillCamHotbar hotbar;
    private KillCams killCams;

    /** The running instance; only valid between {@link #start} and {@link #shutdown}. */
    public static ExyliaKillCam get() {
        return instance;
    }

    /** Starts everything on the Bukkit plugin that loaded this core. */
    public void start(JavaPlugin plugin) {
        instance = this;
        this.plugin = plugin;

        debug = Debug.of(plugin);
        messages = PluginMessages.of(plugin);
        tasks = Tasks.of(plugin);
        teleports = Teleports.of(plugin);

        loadConfigs();
        registerSetting();

        killCams = new KillCams(this);
        killCams.open();
        tasks.runTimer(1200L, 1200L, killCams::sweep);

        Actions.of(plugin, NAMESPACE).registerSync("skip", (ctx, args) -> {
            killCams.skip(ctx.player());
            return ActionResult.success();
        });
        // After the action: compiling the hotbar binds its button to it.
        hotbar = new KillCamHotbar(this);
        hotbar.load();

        plugin.getServer().getPluginManager().registerEvents(new KillCamListener(killCams), plugin);

        BukkitLamp.builder(plugin).build().register(new KillCamCommand());

        reloads = Reloads.of(plugin)
                .step("configs", () -> {
                    Configs.reloadAll(plugin);
                    applyConfigs();
                    registerSetting();
                    killCams.reload();
                })
                .stepAlsoOnLibraryReload("hotbar", hotbar::load);

        debug.motd();
    }

    /** Sends everybody watching back where they were and lets go of the black box. */
    public void shutdown() {
        if (killCams != null) killCams.close();
        // Everything else the library owns for this plugin — actions, overlays, settings, tasks — is
        // released by the library on plugin disable.
        instance = null;
    }

    /**
     * Reads the files.
     *
     * <p>{@code config.yml} first: its {@code language} decides which folder the messages come from.
     */
    private void loadConfigs() {
        KillCamSettings.install(Configs.define(plugin, "config", KillCamSettings.class).version(1).load());
        KillCamMessages.install(Configs.define(plugin, "messages", KillCamMessages.class).translated().load());
        applyConfigs();
    }

    /** What has to follow every read of the files. */
    private void applyConfigs() {
        Prefixes.set(plugin, KillCamMessages.get().prefix());
    }

    /** The choice every player has, on {@code /settings} and {@code /killcam mode}. */
    private void registerSetting() {
        KillCamMessages lines = KillCamMessages.get();
        Settings.register(plugin, Setting.choice(KillCams.settingKey(),
                        KillCamSettings.get().killcam().defaultMode().name().toLowerCase(Locale.ROOT),
                        "auto", "ask", "off")
                .icon("SPYGLASS")
                .name("Killcam")
                .description("What you see after", "somebody kills you.")
                .label("auto", lines.modeAuto())
                .label("ask", lines.modeAsk())
                .label("off", lines.modeOff())
                .permission(Permissions.USE)
                .build());
    }

    /** Reloads this plugin's files and hotbar. */
    public void reload(CommandSender sender) {
        reloads.run(sender);
    }

    public JavaPlugin getPlugin() { return plugin; }
    public TaskScheduler getTasks() { return tasks; }
    public PluginMessages getMessages() { return messages; }
    public PluginTeleports getTeleports() { return teleports; }
    public Debug getDebug() { return debug; }
    public KillCamHotbar getHotbar() { return hotbar; }
    public KillCams getKillCams() { return killCams; }
}
