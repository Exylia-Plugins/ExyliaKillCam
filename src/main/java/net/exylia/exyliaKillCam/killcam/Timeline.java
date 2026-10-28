package net.exylia.exyliaKillCam.killcam;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayMark;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The three moments of a killcam, in the recording's ticks.
 *
 * @param start    where it starts playing
 * @param slowFrom where it slows down; never before {@code start}, and past the
 *                 end when there is no slow motion
 * @param death    where the victim dies and the camera cuts to the body
 */
public record Timeline(int start, int slowFrom, int death) {

    /** Read from a recording: the victim's last death, or its last frame when it holds none. */
    public static @NotNull Timeline of(@NotNull Replay replay, @NotNull UUID victim, int beforeTicks, int slowTicks) {
        int death = -1;
        for (ReplayMark mark : replay.marks()) {
            if (ReplayMark.DEATH.equals(mark.kind()) && victim.equals(mark.actor())) death = mark.tick();
        }
        return of(replay.frames(), death, beforeTicks, slowTicks);
    }

    /**
     * @param frames      how long the recording is
     * @param death       the tick of the death, or below zero when it was not recorded
     * @param beforeTicks how much is shown before it
     * @param slowTicks   how much of that is slowed down; zero or less for none
     */
    public static @NotNull Timeline of(int frames, int death, int beforeTicks, int slowTicks) {
        int last = Math.max(0, frames - 1);
        int at = death < 0 ? last : Math.min(death, last);
        int start = Math.max(0, at - Math.max(0, beforeTicks));
        int slowFrom = slowTicks <= 0 ? Integer.MAX_VALUE : Math.max(start, at - slowTicks);
        return new Timeline(start, slowFrom, at);
    }
}
