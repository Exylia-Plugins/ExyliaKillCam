package net.exylia.exyliaKillCam.killcam;

import net.exylia.lib.replay.Replay;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One death, as the killcam needs it: the figures read when it happened, and
 * the recording, which arrives a moment later.
 *
 * @param victim       who died
 * @param killer       who or what killed them, as the recording knows it
 * @param killerName   their name, or the mob's
 * @param killerHealth the hearts they had left
 * @param weapon       what they held, as plain text
 * @param distance     how far apart the two were, in blocks
 * @param at           when, in milliseconds
 * @param replay       the recording, completed once the seconds after the death are in it
 */
public record Death(@NotNull UUID victim, @NotNull UUID killer, @NotNull String killerName, double killerHealth,
                    @NotNull String weapon, double distance, long at, @NotNull CompletableFuture<Replay> replay) {

    /** Whether it is still recent enough to be watched. */
    public boolean isFresh(long now, long maxAgeMillis) {
        return now - at <= maxAgeMillis;
    }
}
