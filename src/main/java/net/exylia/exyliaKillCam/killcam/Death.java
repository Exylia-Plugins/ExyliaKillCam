package net.exylia.exyliaKillCam.killcam;

import net.exylia.lib.replay.Replay;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One death, as the killcam needs it: the figures read when it happened, and
 * the recording, which arrives a moment later.
 *
 * <p>The recording is kept written out, a few dozen kilobytes, rather than as
 * the objects it is read into, which weigh a dozen times that: a death is kept
 * for minutes in case it is asked for again, and most never are.
 *
 * @param victim       who died
 * @param killer       who or what killed them, as the recording knows it
 * @param killerName   their name, or the mob's
 * @param killerHealth the hearts they had left
 * @param weapon       what they held, as plain text
 * @param distance     how far apart the two were, in blocks
 * @param at           when, in milliseconds
 * @param keepMillis   how long it stays watchable with {@code /killcam}, read from the victim's rank when they died
 * @param recording    the recording, written out; completed once the seconds after the death are in it
 */
public record Death(@NotNull UUID victim, @NotNull UUID killer, @NotNull String killerName, double killerHealth,
                    @NotNull String weapon, double distance, long at, long keepMillis, @NotNull CompletableFuture<byte[]> recording) {

    /** The recording, read back to be watched. */
    public @NotNull CompletableFuture<Replay> replay() {
        return recording.thenApply(Replay::from);
    }

    /** Whether it is still recent enough to be watched. */
    public boolean isFresh(long now, long maxAgeMillis) {
        return now - at <= maxAgeMillis;
    }
}
