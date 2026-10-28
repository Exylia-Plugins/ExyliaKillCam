package net.exylia.exyliaKillCam.config;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;

import java.util.List;

/** The messages of ExyliaKillCam, in {@code lang/<code>/messages.yml}. */
@Comment("Messages sent by ExyliaKillCam.")
@Comment("")
@Comment("Colours use palette tokens such as {primary}, {error} or {highlight},")
@Comment("so recolouring the server never means editing this file.")
@Comment("")
@Comment("A message may open with an effect tag, which is an instruction and never")
@Comment("reaches the screen: [sound:NAME|volume|pitch] plays a sound to the player.")
@Comment("An empty message is not sent at all.")
public record KillCamMessages(
        @Comment("Opens every chat line through %prefix%.")
        String prefix,

        @Comment("")
        @Comment("While a killcam plays. %killer% who made the kill.")
        String title,
        String subtitle,
        @Comment("The line above the hotbar. %killer%, %time% and %length% of the clip, %bar% how far in.")
        String hud,
        String barDone,
        String barLeft,

        @Comment("")
        @Comment("After it. %killer%, %health% the hearts they had left, %weapon% what they held,")
        @Comment("%distance% how far apart you were, in blocks.")
        List<String> summary,
        String fists,

        @Comment("")
        @Comment("For players whose mode is ASK, after they respawn.")
        List<String> offer,

        @Comment("")
        @Comment("Refusals.")
        String noRecentDeath,
        String unsupported,
        String busy,
        String failed,

        @Comment("")
        @Comment("/killcam mode. %mode% is one of the three names below.")
        String modeSet,
        String modeAuto,
        String modeAsk,
        String modeOff,

        @Comment("")
        @Comment("Admin.")
        String reloaded) {

    public KillCamMessages() {
        this("{primary}&lKILLCAM {letters_black}•<reset>",
                "{error}&lKILLCAM",
                "{letters}Killed by {highlight}%killer%",
                "{error}&lKILLCAM {letters_black}» {letters}%killer% {letters_black}▎ %bar% {info}%time%{letters_black}/{info}%length% ⌚",
                "{error}▌",
                "{letters_black}▌",
                List.of("",
                        "%prefix% {letters}Killed by {highlight}%killer%",
                        " {letters_black}▎ {letters}Health left {letters_black}» {error}%health% ❤",
                        " {letters_black}▎ {letters}Weapon {letters_black}» {info}%weapon%",
                        " {letters_black}▎ {letters}Distance {letters_black}» {info}%distance% blocks",
                        " <click:run_command:'/killcam'><hover:show_text:'{letters}Watch your last death again'>{success}&l[▶ WATCH AGAIN]</hover></click>",
                        ""),
                "Fists",
                List.of("",
                        "%prefix% {letters}You were killed by {highlight}%killer%{letters}.",
                        " <click:run_command:'/killcam'><hover:show_text:'{letters}See how it happened'>{success}&l[▶ WATCH KILLCAM]</hover></click>",
                        ""),
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}You have no recent death to watch.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Killcams cannot be shown on this server. {letters_black}PacketEvents is missing.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}You cannot watch a killcam right now.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}The killcam could not be shown.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {letters}Killcam after dying {letters_black}» {highlight}%mode%",
                "{success}shown by itself",
                "{info}offered in chat",
                "{error}off",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {success}Configuration, messages and hotbar reloaded.");
    }

    private static ConfigFile<KillCamMessages> file;

    /** Keeps the loaded file so {@link #get()} reads the current snapshot. */
    public static void install(ConfigFile<KillCamMessages> loaded) {
        file = loaded;
    }

    /** The current snapshot, or the defaults before the file loaded: never {@code null}. */
    public static KillCamMessages get() {
        return file == null ? new KillCamMessages() : file.get();
    }
}
