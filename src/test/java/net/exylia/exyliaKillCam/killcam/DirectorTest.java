package net.exylia.exyliaKillCam.killcam;

import net.exylia.exyliaKillCam.config.KillCamSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DirectorTest {

    @Test
    void theFirstFeatThatFitsWins() {
        assertEquals(Director.Feat.REVENGE, Director.Feat.of(true, 0.5, true, 40, true, 5));
        assertEquals(Director.Feat.CLUTCH, Director.Feat.of(false, 0.5, true, 40, true, 5));
        assertEquals(Director.Feat.LONG_SHOT, Director.Feat.of(false, 10, true, 40, true, 5));
        assertEquals(Director.Feat.ONE_SHOT, Director.Feat.of(false, 10, false, 40, true, 5));
        assertEquals(Director.Feat.STREAK, Director.Feat.of(false, 10, true, 5, false, 3));
        assertEquals(Director.Feat.ELIMINATED, Director.Feat.of(false, 0, false, 5, false, 2));
    }

    @Test
    void theKillersStyleOverridesOnlyWhatItSays() {
        KillCamSettings.Style server = new KillCamSettings.Style();
        assertSame(server, server.over(Map.of()));
        KillCamSettings.Style own = server.over(Map.of("tint", "error", "impact-millis", 600,
                "finale", "rise", "letterbox", false, "shake", "oops"));
        assertEquals("error", own.tint());
        assertEquals(600, own.impactMillis());
        assertEquals(KillCamSettings.Finale.RISE, own.finale());
        assertEquals(false, own.letterbox());
        assertEquals(server.shake(), own.shake());
        assertEquals(server.flash(), own.flash());
    }
}
