package net.exylia.exyliaKillCam.killcam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineTest {

    @Test
    void cutsTheWindowBeforeTheDeath() {
        assertEquals(new Timeline(40, 140, 160), Timeline.of(200, 160, 120, 20));
    }

    @Test
    void neverStartsBeforeTheRecording() {
        assertEquals(new Timeline(0, 30, 50), Timeline.of(200, 50, 120, 20));
    }

    @Test
    void slowMotionNeverStartsBeforeTheStart() {
        assertEquals(new Timeline(90, 90, 100), Timeline.of(200, 100, 10, 40));
    }

    @Test
    void noSlowMotionWhenItIsOff() {
        assertEquals(Integer.MAX_VALUE, Timeline.of(200, 160, 120, 0).slowFrom());
    }

    @Test
    void aDeathThatWasNotRecordedIsTheLastFrame() {
        assertEquals(199, Timeline.of(200, -1, 120, 20).death());
        assertEquals(199, Timeline.of(200, 500, 120, 20).death());
    }

    @Test
    void anEmptyRecordingStaysAtZero() {
        assertEquals(new Timeline(0, 0, 0), Timeline.of(0, -1, 120, 20));
    }
}
