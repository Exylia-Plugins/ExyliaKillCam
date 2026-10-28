package net.exylia.exyliaKillCam.killcam;

import net.exylia.exyliaKillCam.ExyliaKillCam;
import net.exylia.lib.config.Languages;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.Overlays;
import net.exylia.lib.overlay.PluginOverlays;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * The skip button on the viewer's hotbar, from {@code lang/<code>/hotbar.yml}.
 *
 * <p>Drawn on the client, never put in the inventory: the viewer's own items
 * stay underneath and come back when it is hidden.
 */
public final class KillCamHotbar {

    private static final String RESOURCE = "hotbar.yml";

    private final ExyliaKillCam core;
    private final PluginOverlays overlays;
    private volatile OverlayDefinition definition;

    public KillCamHotbar(@NotNull ExyliaKillCam core) {
        this.core = core;
        this.overlays = Overlays.of(core.getPlugin());
    }

    /**
     * Reads the file. Called after the skip action is registered: compiling it
     * binds the button to its action.
     */
    public void load() {
        try {
            if (!Overlays.isAvailable()) return;
            Languages.refresh(core.getPlugin(), ExyliaKillCam.class, RESOURCE);
            File file = Languages.file(core.getPlugin(), RESOURCE);
            if (!file.exists()) {
                core.getDebug().warn("The killcam hotbar (" + RESOURCE + ") is missing; /killcam skip still works.");
                return;
            }
            definition = overlays.load("killcam", YamlConfiguration.loadConfiguration(file));
        } catch (RuntimeException | LinkageError broken) {
            core.getDebug().error("The killcam hotbar did not load: " + broken);
        }
    }

    public void show(@NotNull Player viewer) {
        OverlayDefinition ready = definition;
        if (ready != null) overlays.show(viewer, ready);
    }

    public void hide(@NotNull Player viewer) {
        if (definition != null && overlays.isShowing(viewer)) overlays.hide(viewer);
    }
}
