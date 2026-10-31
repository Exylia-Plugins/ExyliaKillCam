package net.exylia.exyliaKillCam;

import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.util.PermissionNumbers;
import org.bukkit.entity.Player;

/**
 * The permission nodes this plugin checks, as plugin.yml declares them.
 *
 * <p>Every player feature has a node everybody holds by default, so a server makes one a rank
 * perk by denying it to the default group. Skipping a killcam is never behind one.
 */
public final class Permissions {

    /** Get a killcam after dying. */
    public static final String USE = "exyliakillcam.use";

    /** Watch the last death again with {@code /killcam}. */
    public static final String REWATCH = "exyliakillcam.rewatch";

    /** Choose what happens after dying, with {@code /killcam mode} or {@code /settings}. */
    public static final String MODE = "exyliakillcam.mode";

    /** {@code rewatch.<n>}: how many minutes the last death stays watchable. The highest wins. */
    public static final String REWATCH_MINUTES_PREFIX = "exyliakillcam.rewatch.";

    /** Reload the files. */
    public static final String ADMIN = "exyliakillcam.admin";

    private Permissions() {
        throw new AssertionError("No instances.");
    }

    /** How long this player's last death stays watchable: {@code rewatch.<n>} minutes, or {@code rewatch-minutes}. */
    public static long rewatchMillis(Player player) {
        int minutes = PermissionNumbers.highest(player, REWATCH_MINUTES_PREFIX, KillCamSettings.get().killcam().rewatchMinutes());
        return Math.max(1, minutes) * 60_000L;
    }
}
