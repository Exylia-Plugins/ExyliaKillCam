package net.exylia.exyliaKillCam.config;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Languages;
import net.exylia.lib.config.Time;

import java.util.List;

/**
 * Settings stored in {@code config.yml}.
 */
public record KillCamSettings(
        @Comment("Language of this plugin's messages and hotbar. 'default' follows the language set in")
        @Comment("ExyliaLib's config.yml; en, es, pt, fr, ru or tr set this plugin alone. Each one is a folder under")
        @Comment("lang/ you can edit.")
        String language,

        @Comment("")
        @Comment("What a killcam shows and for how long.")
        Killcam killcam,

        @Comment("")
        @Comment("Which deaths get a killcam.")
        Deaths deaths,

        @Comment("")
        @Comment("The recording every killcam is cut from: the last seconds around every player, kept in memory.")
        Recording recording,

        @Comment("")
        @Comment("ExyliaKillEffect, when it is installed.")
        KillEffects killEffects) {

    public KillCamSettings() {
        this(Languages.DEFAULT, new Killcam(), new Deaths(), new Recording(), new KillEffects());
    }

    /** What a player gets after dying, until they pick otherwise with /killcam mode. */
    public enum Mode {
        /** The killcam starts by itself after the respawn. */
        AUTO,
        /** A clickable line in chat offers it. */
        ASK,
        /** Nothing; /killcam still shows the last death. */
        OFF
    }

    /** The {@code killcam} section. */
    public record Killcam(
            @Comment("What players get until they choose with /killcam mode: AUTO, ASK or OFF.")
            Mode defaultMode,

            @Comment("How much of the fight is shown before the death.")
            @Time(Time.Unit.SECONDS) int secondsBefore,

            @Comment("How long the last moment before the death plays in slow motion, in seconds. 0 = never.")
            double slowMotionSeconds,

            @Comment("How fast that slow motion plays: 0.5 is half speed.")
            double slowMotionSpeed,

            @Comment("Whether the killcam is watched through the killer's eyes. false = from behind the victim.")
            boolean firstPerson,

            @Comment("How long the camera stays on the body after the death, for the fall and the kill effect.")
            @Time(Time.Unit.SECONDS) int holdSeconds,

            @Comment("Whether players respawn by themselves, so the killcam follows the death without a click.")
            @Comment("Only for players whose mode is AUTO.")
            boolean autoRespawn,

            @Comment("How long /killcam can still show a player's last death.")
            @Time(Time.Unit.MINUTES) int rewatchMinutes) {

        public Killcam() {
            this(Mode.AUTO, 6, 1.0, 0.35, true, 3, true, 5);
        }
    }

    /** The {@code deaths} section. */
    public record Deaths(
            @Comment("Whether a death to a mob gets a killcam too, not only a death to a player.")
            boolean mobs,

            @Comment("Worlds where nobody gets a killcam.")
            List<String> disabledWorlds,

            @Comment("Whether a death another plugin is in charge of (a duel, an event, a match, an FFA arena,")
            @Comment("anything that holds the player or takes over what they drop) waits until that plugin lets")
            @Comment("the player go, and plays then. Keep it on.")
            boolean skipHeldPlayers) {

        public Deaths() {
            this(false, List.of(), true);
        }
    }

    /** The {@code recording} section. */
    public record Recording(
            @Comment("How far around the victim the killcam shows what happened, in blocks (8-96).")
            double radius,

            @Comment("Whether players in vanish are kept out of every killcam.")
            boolean hideVanished) {

        public Recording() {
            this(24, true);
        }
    }

    /** The {@code kill-effects} section. */
    public record KillEffects(
            @Comment("Whether the killer's kill effect plays again, for the viewer alone, when the body falls.")
            @Comment("Needs ExyliaKillEffect 1.9.0 or newer; older versions are skipped without a word.")
            boolean replay) {

        public KillEffects() {
            this(true);
        }
    }

    private static ConfigFile<KillCamSettings> file;

    /** Keeps the loaded file so {@link #get()} reads the current snapshot. */
    public static void install(ConfigFile<KillCamSettings> loaded) {
        file = loaded;
    }

    /** The current snapshot, or the defaults before the file loaded: never {@code null}. */
    public static KillCamSettings get() {
        return file == null ? new KillCamSettings() : file.get();
    }
}
