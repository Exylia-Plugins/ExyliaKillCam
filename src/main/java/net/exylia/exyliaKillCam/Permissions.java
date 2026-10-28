package net.exylia.exyliaKillCam;

/**
 * The permission nodes this plugin checks, as plugin.yml declares them.
 */
public final class Permissions {

    /** Get a killcam after dying, and watch the last death again with {@code /killcam}. */
    public static final String USE = "exyliakillcam.use";

    /** Reload the files. */
    public static final String ADMIN = "exyliakillcam.admin";

    private Permissions() {
        throw new AssertionError("No instances.");
    }
}
