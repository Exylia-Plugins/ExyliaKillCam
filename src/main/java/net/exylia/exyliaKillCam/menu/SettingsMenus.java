package net.exylia.exyliaKillCam.menu;

import net.exylia.exyliaKillCam.ExyliaKillCam;
import net.exylia.exyliaKillCam.Permissions;
import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.ConfigEdits;
import net.exylia.lib.config.Languages;
import net.exylia.lib.ui.Menus;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiKeys;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings an admin really tunes, chosen and explained by hand: a hub with one card per
 * theme, each opening a page of that theme's settings. Everything else stays in
 * {@code config.yml}.
 *
 * <p>Every page is {@code admin/settings_<page>}; its values are {@code %<page>_<key>%} (the YAML
 * key with dots and dashes as underscores) and its cards run {@code exyliakillcam:setting <page> <key>}.
 */
public final class SettingsMenus {

    static final String HUB = "admin/settings";

    /** A page and the two keys its hub card shows. */
    private record Page(String... shown) {
    }

    private static final Map<String, Page> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("playback", new Page("killcam.seconds-before", "killcam.slow-motion-seconds"));
        PAGES.put("deaths", new Page("killcam.rewatch-minutes", "deaths.mobs"));
    }

    /** Every screen this class draws, as menu ids. */
    static final List<String> IDS;

    static {
        List<String> ids = new java.util.ArrayList<>(List.of(HUB));
        PAGES.keySet().forEach(id -> ids.add(HUB + "_" + id));
        IDS = List.copyOf(ids);
    }

    private final ExyliaKillCam core;
    private final PluginMenus menus;

    public SettingsMenus(ExyliaKillCam core) {
        this.core = core;
        this.menus = Menus.of(core.getPlugin(), ExyliaKillCam.NAMESPACE);
    }

    /** Compiles the screens; the folder is rewritten from the jar on every start. */
    public void load() {
        Languages.replace(core.getPlugin(), SettingsMenus.class, "menus/admin");
        for (String id : IDS) {
            File file = Languages.file(core.getPlugin(), "menus/" + id + ".yml");
            try {
                menus.load(id, YamlConfiguration.loadConfiguration(file));
            } catch (RuntimeException broken) {
                core.getDebug().error("Menu \"" + id + "\" (" + file.getPath()
                        + ") did not load and will not open: " + broken.getMessage());
            }
        }
    }

    public void reload() {
        menus.unload();
        load();
    }

    /** The hub: only the values its cards show, since every live value is read each second. */
    public void openHub(Player admin) {
        Map<String, Object> context = new LinkedHashMap<>();
        PAGES.forEach((id, page) -> context.putAll(edits().placeholders(id + "_", List.of(page.shown()))));
        open(admin, HUB, context);
    }

    private void openPage(Player admin, String id) {
        if (!PAGES.containsKey(id)) return;
        open(admin, HUB + "_" + id, new LinkedHashMap<>(edits().placeholders(id + "_")));
    }

    private void open(Player admin, String id, Map<String, Object> context) {
        core.getTasks().runAtEntity(admin, () -> menus.definition(id)
                .ifPresentOrElse(definition -> menus.openNow(admin, definition, context, Map.of()),
                        () -> core.getDebug().warn("Something asked to open the menu \"" + id + "\", which is not loaded.")));
    }

    public void register(PluginActions actions) {
        actions.registerSync("settings_hub", Permissions.ADMIN, (ctx, args) -> {
            openHub(ctx.player());
            return ActionResult.success();
        });
        actions.registerSync("settings_page", Permissions.ADMIN, (ctx, args) -> {
            openPage(ctx.player(), args.string(0, ""));
            return ActionResult.success();
        });
        actions.registerSync("setting", Permissions.ADMIN, (ctx, args) -> {
            Player admin = ctx.player();
            String id = args.string(0, "");
            if (PAGES.containsKey(id)) {
                edits().card(admin, args.string(1, ""), ctx.get(UiKeys.CLICK).orElse(null),
                        () -> openPage(admin, id));
            }
            return ActionResult.success();
        });
    }

    private ConfigEdits edits() {
        return ConfigEdits.of(core.getPlugin(), KillCamSettings::file);
    }
}
