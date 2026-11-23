package net.exylia.exyliaKillCam.killcam;

import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.camera.CameraScreen;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.replay.ReplayViewer;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/**
 * How one killcam is filmed, after the killer's eyes: a close-up that slows
 * into the killing blow (or follows the projectile that lands it), the blow
 * frozen on black with only the two of them outlined and a title slammed over
 * it, then a flash and a last shot around the body while the killer's line is
 * written at the foot.
 *
 * <p>{@link #step()} runs on the viewer's thread every tick and moves from one
 * part to the next; {@link #aim} is read on the playback's thread every frame
 * and places the camera. What they share is volatile.
 */
final class Director implements ReplayPlayback.Shot {

    /** From how far a projectile kill is a long shot, in blocks. */
    static final double LONG_SHOT_BLOCKS = 20;

    private static final int BLACK = 0xFF000000;
    private static final long TINT_MILLIS = 650;
    private static final long FLASH_MILLIS = 450;
    private static final long TITLE_MILLIS = 1600;
    /** How big the title starts, times its size, and how long it takes to land. */
    private static final double TITLE_FROM = 2.5;
    private static final long TITLE_LANDS = 160;

    /** The close-up: beside the two of them, a little on the killer's side, pushing in. */
    private static final double PUSH_FROM = 4.2;
    private static final double PUSH_TO = 2.6;
    private static final double SIDE = Math.toRadians(65);
    private static final double EYE = 1.75;
    private static final double CHEST = 1.1;
    /** The chase: behind the projectile and a little above, looking a little ahead. */
    private static final double CHASE_BEHIND = 2.2;
    private static final double CHASE_ABOVE = 0.35;
    private static final double CHASE_AHEAD = 1.5;
    /** The finale, in radians, and blocks, a second; how near the killer must be to share it. */
    private static final double ORBIT_SPEED = 0.45;
    private static final double RISE_SPEED = 0.9;
    private static final double RISE_SECONDS = 4;
    private static final double SHARED = 8;
    private static final double CLEARANCE = 2.5;

    enum Part { OPENING, CLOSE, IMPACT, FINALE }

    /** What the title over the killing blow says, the first that fits. */
    enum Feat {
        REVENGE, CLUTCH, LONG_SHOT, ONE_SHOT, STREAK, ELIMINATED;

        static @NotNull Feat of(boolean revenge, double killerHearts, boolean projectile, double distance,
                                boolean oneHit, int streak) {
            if (revenge) return REVENGE;
            if (killerHearts > 0 && killerHearts <= 1) return CLUTCH;
            if (projectile && distance >= LONG_SHOT_BLOCKS) return LONG_SHOT;
            if (oneHit) return ONE_SHOT;
            if (streak >= 3) return STREAK;
            return ELIMINATED;
        }
    }

    private final Player viewer;
    private final ReplayViewer open;
    private final ReplayPlayback playback;
    private final Death death;
    private final KillCamSettings.Style style;
    private final KillCamSettings.Killcam settings;
    private final Timeline timeline;
    private final @Nullable CameraScreen screen;
    private final Component title;
    private final Component line;
    private final IntFunction<Component> caption;
    /** Called on the blow, to sound the title as it lands. */
    private final Runnable landed;
    /** Called as the last shot starts: the killer's kill effect again. */
    private final Runnable finale;

    private volatile Part part = Part.OPENING;
    private volatile int closeFrom;
    private volatile long impactAt;
    private volatile long finaleAt;
    private double speed = 1;
    private boolean titleGone;

    // The shot's own, read and written on the playback's thread.
    private Location body;
    private Location last;
    private Location frozen;
    private Vector flown;
    private Vector heading;
    private Vector launch;
    private int captioned = -1;
    private double angle;
    private double radius;
    private double height;
    private Location middle;

    Director(@NotNull Player viewer, @NotNull ReplayViewer open, @NotNull Death death, @NotNull Timeline timeline,
             @NotNull KillCamSettings.Killcam settings, @NotNull Component title, @NotNull Component line,
             @NotNull IntFunction<Component> caption, @NotNull Runnable landed, @NotNull Runnable finale) {
        this.viewer = viewer;
        this.open = open;
        this.playback = open.playback();
        this.death = death;
        this.style = death.style();
        this.settings = settings;
        this.timeline = timeline;
        this.screen = playback.screen(viewer);
        this.title = title;
        this.line = line;
        this.caption = caption;
        this.landed = landed;
        this.finale = finale;
    }

    // ------------------------------------------------------------ the parts

    /** Every tick, on the viewer's thread: {@code true} once it is over. */
    boolean step() {
        int tick = playback.tick();
        long now = System.currentTimeMillis();
        if (part == Part.OPENING && (tick >= timeline.slowFrom() || tick >= timeline.death() || flying())) close(tick);
        if (part == Part.CLOSE) {
            if (tick >= timeline.death()) impact(now);
            else slow(tick);
        }
        if (part == Part.IMPACT && now - impactAt >= Math.max(0, style.impactMillis())) end(now);
        if (part != Part.FINALE) return false;
        if (!titleGone && now - finaleAt >= TITLE_MILLIS) {
            titleGone = true;
            if (screen != null) screen.title(null, 1, 0);
        }
        return now - finaleAt >= Math.max(1, settings.holdSeconds()) * 1000L;
    }

    /** The projectile that lands the blow is in the air. */
    private boolean flying() {
        UUID projectile = death.projectile();
        return projectile != null && style.projectileCam() && playback.drawnAt(projectile) != null;
    }

    /** Out of the killer's eyes and onto the two of them, with the bars and the tint. */
    private void close(int tick) {
        closeFrom = tick;
        // The server's view again: the close-up shows where everybody really was.
        open.follow(null, ReplayPlayback.View.SERVER);
        playback.direct(viewer, this);
        if (screen != null) {
            screen.letterbox(style.letterbox());
            // A cut through black: off a player's eyes the client eases the
            // camera down to the new body over a few ticks, panels and all.
            screen.fill(BLACK, 0);
            screen.fill(argb(style.tint(), style.tintOpacity()), TINT_MILLIS);
        }
        part = Part.CLOSE;
    }

    /** Slower and slower into the blow. */
    private void slow(int tick) {
        double slowest = settings.slowMotionSeconds() > 0 ? Math.clamp(settings.slowMotionSpeed(), 0.0625, 1.0) : 1;
        double wanted = 1 + (slowest - 1) * eased(progress(tick));
        if (Math.abs(wanted - speed) < 0.02) return;
        speed = wanted;
        playback.speed(wanted);
    }

    /** The blow, frozen: black, the two of them outlined, the title slammed over it. */
    private void impact(long now) {
        playback.pause();
        impactAt = now;
        if (screen != null) {
            if (style.impactFrame()) {
                screen.fill(BLACK, 0);
                playback.highlight(List.of(death.killer(), death.victim()));
            }
            screen.title(title, TITLE_FROM, TITLE_LANDS);
            // The count lands on the title's number: it runs from the eyes to the chest, the title feet to feet.
            if (captioned >= 0) screen.caption(caption.apply((int) Math.round(death.distance())));
        }
        sound(viewer, style.impactSound());
        landed.run();
        part = Part.IMPACT;
    }

    /** A flash, the scene back in real time, and the last shot around the body. */
    private void end(long now) {
        finaleAt = now;
        playback.highlight(List.of());
        if (screen != null) {
            int flash = argb(style.flash(), style.flashOpacity());
            if (flash != 0) screen.fill(flash, 0);
            screen.fill(0, FLASH_MILLIS);
            screen.caption(null);
            screen.line(line, style.typewriter());
        }
        playback.speed(1.0);
        playback.resume();
        part = Part.FINALE;
        finale.run();
    }

    // -------------------------------------------------------------- the shot

    @Override
    public @Nullable Location aim(@NotNull ReplayPlayback playback) {
        Location victim = playback.drawnAt(death.victim());
        if (victim != null) body = victim;
        if (body == null) return null;
        Location shot = switch (part) {
            case OPENING -> null;
            case CLOSE -> {
                Location chase = chase(playback);
                yield chase != null ? chase : closeUp(playback, eased(progress(playback.tick())));
            }
            case IMPACT -> shaken(playback);
            case FINALE -> around(playback);
        };
        if (shot != null) last = shot;
        return shot;
    }

    /**
     * Beside the two of them, a little on the killer's side and looking a
     * little towards them, closer the nearer the blow. Straight behind the killer a
     * melee blow would be filmed through their shoulder.
     */
    private Location closeUp(ReplayPlayback playback, double near) {
        Location killer = playback.drawnAt(death.killer());
        Vector toward = killer != null && killer.getWorld() == body.getWorld()
                ? killer.toVector().subtract(body.toVector()).setY(0)
                : body.getDirection().setY(0);
        double apart = toward.length();
        if (apart < 1.0E-2) toward = new Vector(0, 0, 1);
        toward.normalize();
        Vector across = new Vector(-toward.getZ(), 0, toward.getX());
        double reach = PUSH_FROM + (PUSH_TO - PUSH_FROM) * near;
        Vector off = toward.clone().multiply(Math.cos(SIDE)).add(across.multiply(Math.sin(SIDE))).multiply(reach);
        Location seat = body.clone().add(off).add(0, EYE, 0);
        return looking(seat, body.clone().add(toward.multiply(Math.min(1, apart / 3))).add(0, CHEST, 0));
    }

    /** Behind the projectile in flight, with how far it has come over the view. */
    private @Nullable Location chase(ReplayPlayback playback) {
        UUID projectile = death.projectile();
        if (projectile == null || !style.projectileCam()) return null;
        Location at = playback.drawnAt(projectile);
        if (at == null) return null;
        Vector here = at.toVector();
        if (launch == null) {
            // From the bow: the arrow's first frame is already a few blocks out, and the
            // killer's body is not drawn yet on the tick the camera leaves their eyes.
            Location killer = playback.locationOf(death.killer());
            launch = killer != null && killer.getWorld() == at.getWorld()
                    ? killer.toVector().add(new Vector(0, EYE, 0)) : here.clone();
        }
        if (flown != null && here.distanceSquared(flown) > 1.0E-6) heading = here.clone().subtract(flown).normalize();
        if (heading == null) {
            Vector toward = body.toVector().add(new Vector(0, CHEST, 0)).subtract(here);
            heading = toward.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : toward.normalize();
        }
        flown = here;
        int blocks = (int) Math.round(launch.distance(here));
        if (blocks != captioned && screen != null) {
            captioned = blocks;
            screen.caption(caption.apply(blocks));
        }
        Location seat = at.clone().subtract(heading.clone().multiply(CHASE_BEHIND)).add(0, CHASE_ABOVE, 0);
        return looking(seat, at.clone().add(heading.clone().multiply(CHASE_AHEAD)));
    }

    /** Where the blow was seen from, shaking less and less. */
    private Location shaken(ReplayPlayback playback) {
        if (frozen == null) frozen = last != null ? last : closeUp(playback, 1);
        double left = 1 - Math.min(1, (System.currentTimeMillis() - impactAt) / (double) Math.max(1, style.impactMillis()));
        double reach = Math.max(0, style.shake()) * left * left;
        if (reach <= 0) return frozen;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return frozen.clone().add(random.nextDouble(-reach, reach), random.nextDouble(-reach, reach),
                random.nextDouble(-reach, reach));
    }

    /**
     * The last shot, from where the blow was seen, around the body and the
     * killer when they stand near it, far enough out to pass neither.
     */
    private Location around(ReplayPlayback playback) {
        Location from = frozen != null ? frozen : last;
        if (from == null) return null;
        if (middle == null) {
            middle = body.clone();
            double reach = 0;
            Location killer = playback.drawnAt(death.killer());
            if (killer != null && killer.getWorld() == body.getWorld()) {
                double apart = Math.hypot(killer.getX() - body.getX(), killer.getZ() - body.getZ());
                if (apart <= SHARED) {
                    middle.add(killer.toVector().subtract(body.toVector()).setY(0).multiply(0.5));
                    reach = apart / 2;
                }
            }
            angle = Math.atan2(from.getZ() - middle.getZ(), from.getX() - middle.getX());
            radius = Math.max(reach + CLEARANCE, Math.hypot(from.getX() - middle.getX(), from.getZ() - middle.getZ()));
            height = from.getY() - middle.getY();
        }
        double seconds = (System.currentTimeMillis() - finaleAt) / 1000.0;
        Location centre = middle.clone().add(0, CHEST * 0.7, 0);
        return switch (style.finale()) {
            case HOLD -> looking(from.clone(), centre);
            case ORBIT -> {
                double turned = angle + seconds * ORBIT_SPEED;
                yield looking(middle.clone().add(Math.cos(turned) * radius, height, Math.sin(turned) * radius), centre);
            }
            case RISE -> {
                double risen = Math.min(seconds, RISE_SECONDS) * RISE_SPEED;
                double out = radius + risen * 0.6;
                yield looking(middle.clone().add(Math.cos(angle) * out, height + risen, Math.sin(angle) * out), centre);
            }
        };
    }

    // --------------------------------------------------------------- helpers

    private double progress(int tick) {
        double span = Math.max(1, timeline.death() - closeFrom);
        return Math.clamp((tick - closeFrom) / span, 0.0, 1.0);
    }

    private static double eased(double t) {
        return t * t * (3 - 2 * t);
    }

    private static Location looking(Location seat, Location at) {
        Vector toward = at.toVector().subtract(seat.toVector());
        if (toward.lengthSquared() > 1.0E-6) seat.setDirection(toward);
        return seat;
    }

    /** A sound written {@code name|volume|pitch}, to the player alone; nothing when it is empty or wrong. */
    static void sound(@NotNull Player player, @Nullable String written) {
        if (written == null || written.isBlank()) return;
        String[] parts = written.trim().split("\\|");
        try {
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            player.playSound(player, parts[0].toLowerCase(java.util.Locale.ROOT), volume, pitch);
        } catch (NumberFormatException wrong) {
            // A typo in the file is silence, not an error every death.
        }
    }

    /** A palette colour, without braces, at an opacity from 0 to 1, as ARGB; 0 when there is none. */
    static int argb(@Nullable String token, double opacity) {
        if (token == null || opacity <= 0) return 0;
        TextColor colour = Colors.get(token.replace("{", "").replace("}", "").trim());
        if (colour == null) return 0;
        return (int) Math.round(Math.min(1, opacity) * 255) << 24 | colour.value();
    }
}
