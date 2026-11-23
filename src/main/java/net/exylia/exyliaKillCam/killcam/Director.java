package net.exylia.exyliaKillCam.killcam;

import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.camera.CameraScreen;
import net.exylia.lib.camera.CameraWalls;
import net.exylia.lib.replay.Replay;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
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
    /** Where else around the two of them the close-up may stand when a wall or a tree hides one, best first. */
    private static final double[] SWINGS = {SIDE, Math.toRadians(90), Math.toRadians(40), Math.toRadians(115)};
    /** And how high above eye level, when no place at eye level frames them: a tight room is filmed from above its walls. */
    private static final double[] LIFTS = {0, 1.6, 3.2, 4.8};
    /** How far above where the blow was seen the last shot may fly when walls crowd its path, best first. */
    private static final double[] RAISES = {0, 1.6, 3.2, 4.8};
    /** The open air a body keeps either side of it in the frame, and how near the lens it may stand, in blocks. */
    private static final double BESIDE = 0.9;
    private static final double NEAREST = 2.0;
    /** How near the line to a body another one stands to cover it, in blocks. */
    private static final double COVERS = 0.7;
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
    /** How well the last shot's whole path must frame them to be taken at once, and every how many seconds it is checked. */
    private static final double GOOD = 0.85;
    private static final double SAMPLE = 0.5;
    /** How fast a camera pulled in by a wall eases back out once it is past it, in blocks a tick. */
    private static final double ROOM_BACK = 0.25;

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
    /** The tick the projectile that lands the blow is first seen, or -1 when it is not followed or was not recorded. */
    private final int shotAt;
    private final @Nullable CameraScreen screen;
    private final Component title;
    private final Component line;
    private final IntFunction<Component> caption;
    /** Called on the blow, to sound the title as it lands. */
    private final Runnable landed;
    /** Called as the last shot starts: the killer's kill effect again. */
    private final Runnable finale;
    /** Runs a task on the thread that owns a place: where the walls are read. */
    private final BiConsumer<Location, Runnable> atPlace;

    private volatile Part part = Part.OPENING;
    private volatile int closeFrom;
    private volatile long impactAt;
    private volatile long finaleAt;
    private double speed = 1;
    private boolean titleGone;
    /** The blocks around the scene, read once the close-up starts; until then nothing is in the way. */
    private volatile @Nullable CameraWalls walls;

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
    /** What the last shot keeps in sight: the ground the body lies on, and the killer standing by. */
    private List<Location> finaleSeen = List.of();
    /** Whether the last shot starts from where the blow was seen, or that place was too crowded. */
    private boolean fromBlow = true;
    /** Which way the orbit turns, and how far above where the blow was seen it flies: away from the walls. */
    private int turn = 1;
    private double raise;
    /** Which side of the two the close-up films from, and how far round, once the walls say where has room. */
    private int side;
    private double swing = SIDE;
    private double lift;
    /** How far from what it films a camera may stand now, and for which part. */
    private double room = Double.MAX_VALUE;
    private @Nullable Location wantedLast;

    Director(@NotNull Player viewer, @NotNull ReplayViewer open, @NotNull Death death, @NotNull Timeline timeline,
             @NotNull KillCamSettings.Killcam settings, @NotNull Component title, @NotNull Component line,
             @NotNull IntFunction<Component> caption, @NotNull Runnable landed, @NotNull Runnable finale,
             @NotNull BiConsumer<Location, Runnable> atPlace) {
        this.viewer = viewer;
        this.open = open;
        this.playback = open.playback();
        this.death = death;
        this.style = death.style();
        this.settings = settings;
        this.timeline = timeline;
        this.shotAt = shotAt(playback.replay(), death.projectile() != null && style.projectileCam()
                ? death.projectile() : null, timeline);
        this.screen = playback.screen(viewer);
        this.title = title;
        this.line = line;
        this.caption = caption;
        this.landed = landed;
        this.finale = finale;
        this.atPlace = atPlace;
    }

    // ------------------------------------------------------------ the parts

    /** Every tick, on the viewer's thread: {@code true} once it is over. */
    boolean step() {
        int tick = playback.tick();
        long now = System.currentTimeMillis();
        if (part == Part.OPENING && (tick >= timeline.death() || flying() || tick >= timeline.slowFrom() && !aiming(tick))) {
            close(tick);
        }
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

    /**
     * A projectile kill stays in the killer's eyes until the shot leaves:
     * reaching the body first would cut back to the bow when it does.
     */
    private boolean aiming(int tick) {
        return tick < shotAt;
    }

    static int shotAt(@NotNull Replay replay, @Nullable UUID projectile, @NotNull Timeline timeline) {
        if (projectile == null) return -1;
        for (int tick = timeline.start(); tick <= timeline.death(); tick++) {
            if (replay.at(tick, projectile).present()) return tick;
        }
        return -1;
    }

    /** Out of the killer's eyes and onto the two of them, with the bars and the tint. */
    private void close(int tick) {
        closeFrom = tick;
        // The server's view again: the close-up shows where everybody really was.
        open.follow(null, ReplayPlayback.View.SERVER);
        // Read where the two of them are now, on that place's own thread; the
        // camera asks the copy every frame from the playback's.
        Location body = playback.locationOf(death.victim());
        Location killer = playback.locationOf(death.killer());
        if (body != null) {
            Location other = killer != null ? killer : body;
            atPlace.accept(body, () -> walls = CameraWalls.around(other, body));
        }
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

    /** Slower and slower into the blow; a followed shot flies at the slowest from the bow, or it is gone in a blink. */
    private void slow(int tick) {
        double slowest = settings.slowMotionSeconds() > 0 ? Math.clamp(settings.slowMotionSpeed(), 0.0625, 1.0) : 1;
        double wanted = shotAt >= 0 ? slowest : 1 + (slowest - 1) * eased(progress(tick));
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
        Location at = body.clone().add(toward.clone().multiply(Math.min(1, apart / 3))).add(0, CHEST, 0);
        List<Location> seen = new ArrayList<>(2);
        seen.add(body.clone().add(0, CHEST, 0));
        if (killer != null && killer.getWorld() == body.getWorld()) seen.add(killer.clone().add(0, CHEST, 0));
        CameraWalls walls = this.walls;
        if (side == 0 && walls != null) {
            // Chosen once, under the cut through black: the first place that frames both of
            // them cleanly from as far as it wants, or else the one that comes nearest.
            double best = -1;
            for (double up : LIFTS) {
                for (double around : SWINGS) {
                    for (int either : new int[]{1, -1}) {
                        // Judged where it starts and where it ends up, pushed in on the blow.
                        double score = Math.min(
                                framing(walls, at, seat(toward, across, either, around, PUSH_FROM).add(0, up, 0), seen),
                                framing(walls, at, seat(toward, across, either, around, PUSH_TO).add(0, up, 0), seen));
                        if (score > best + 0.05) {
                            best = score;
                            side = either;
                            swing = around;
                            lift = up;
                        }
                    }
                }
            }
        }
        return looking(seat(toward, across, side == 0 ? 1 : side, swing, reach).add(0, lift, 0), at, seen);
    }

    /**
     * How well a seat frames the two of them, from 0 to 1: how much of its
     * distance it keeps in front of the walls, halved for each of them a wall or
     * a tree hides or crowds in the frame, and less again for one standing right
     * in front of the lens or in front of the other.
     */
    private static double framing(CameraWalls walls, Location at, Location wanted, List<Location> seen) {
        Location seat = walls.clear(at, wanted, seen);
        double score = seat.distance(at) / Math.max(1.0E-3, wanted.distance(at));
        Vector look = at.toVector().subtract(seat.toVector()).setY(0);
        Vector beside = look.lengthSquared() < 1.0E-6 ? new Vector()
                : new Vector(-look.getZ(), 0, look.getX()).normalize().multiply(BESIDE);
        for (Location point : seen) {
            // From the seat out: a trunk right beside a body is entered on the way, even with the point inside it.
            if (!walls.sees(seat, point) || !walls.sees(seat, point.clone().add(beside))
                    || !walls.sees(seat, point.clone().subtract(beside))) score *= 0.5;
            if (point.distance(seat) < NEAREST) score *= 0.3;
            for (Location other : seen) {
                if (other != point && inFront(seat, point, other)) score *= 0.4;
            }
        }
        return score;
    }

    /** Whether {@code front} stands on the way from the seat to {@code behind}, near enough to cover it. */
    private static boolean inFront(Location seat, Location front, Location behind) {
        double dx = behind.getX() - seat.getX();
        double dz = behind.getZ() - seat.getZ();
        double length = dx * dx + dz * dz;
        if (length < 1.0E-6) return false;
        double t = ((front.getX() - seat.getX()) * dx + (front.getZ() - seat.getZ()) * dz) / length;
        if (t <= 0 || t >= 1) return false;
        return Math.hypot(seat.getX() + dx * t - front.getX(), seat.getZ() + dz * t - front.getZ()) < COVERS;
    }

    private Location seat(Vector toward, Vector across, int side, double swing, double reach) {
        Vector off = toward.clone().multiply(Math.cos(swing)).add(across.clone().multiply(side * Math.sin(swing)));
        return body.clone().add(off.multiply(reach)).add(0, EYE, 0);
    }

    private static double roomAt(CameraWalls walls, Location at, Location seat, List<Location> seen) {
        return walls.clear(at, seat, seen).distance(at);
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
        // Never behind the bow: just out of the bow the head would fill the frame.
        double behind = Math.min(CHASE_BEHIND, Math.max(0, launch.distance(here) - 0.4));
        Location seat = at.clone().subtract(heading.clone().multiply(behind)).add(0, CHASE_ABOVE, 0);
        return looking(seat, at.clone().add(heading.clone().multiply(CHASE_AHEAD)), List.of());
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
            List<Location> seen = new ArrayList<>(2);
            // The body lies by now: keep the ground it lies on in sight, not where its chest stood.
            seen.add(body.clone().add(0, 0.4, 0));
            if (killer != null && killer.getWorld() == body.getWorld()) {
                double apart = Math.hypot(killer.getX() - body.getX(), killer.getZ() - body.getZ());
                if (apart <= SHARED) {
                    middle.add(killer.toVector().subtract(body.toVector()).setY(0).multiply(0.5));
                    reach = apart / 2;
                    seen.add(killer.clone().add(0, CHEST, 0));
                }
            }
            finaleSeen = List.copyOf(seen);
            angle = Math.atan2(from.getZ() - middle.getZ(), from.getX() - middle.getX());
            radius = Math.max(reach + CLEARANCE, Math.hypot(from.getX() - middle.getX(), from.getZ() - middle.getZ()));
            height = from.getY() - middle.getY();
            turnToRoom();
        }
        double seconds = (System.currentTimeMillis() - finaleAt) / 1000.0;
        Location centre = middle.clone().add(0, CHEST * 0.7, 0);
        List<Location> seen = finaleSeen;
        boolean still = style.finale() == KillCamSettings.Finale.HOLD && fromBlow;
        return looking(still ? from.clone() : finaleSeat(angle, turn, raise, seconds), centre, seen);
    }

    /** Where the last shot stands a number of seconds in, starting at an angle around them, turning one way, raised. */
    private Location finaleSeat(double start, int way, double up, double seconds) {
        double turned = start;
        double out = radius;
        double above = height + up;
        switch (style.finale()) {
            case ORBIT -> turned += way * seconds * ORBIT_SPEED;
            case RISE -> {
                double risen = Math.min(seconds, RISE_SECONDS) * RISE_SPEED;
                out += risen * 0.6;
                above += risen;
            }
            case HOLD -> { }
        }
        return middle.clone().add(Math.cos(turned) * out, above, Math.sin(turned) * out);
    }

    /**
     * The last shot starts where the blow was seen unless a wall or a tree
     * crowds the two of them somewhere on its path: then the orbit turns the
     * other way, or starts from the nearest angle around them that frames them
     * cleanly all along, or flies higher, over the walls of a tight room. The
     * impact frame's black hides the cut.
     */
    private void turnToRoom() {
        CameraWalls walls = this.walls;
        if (walls == null) return;
        Location centre = middle.clone().add(0, CHEST * 0.7, 0);
        KillCamSettings.Finale kind = style.finale();
        double span = switch (kind) {
            case HOLD -> 0;
            case RISE -> Math.min(RISE_SECONDS, Math.max(1, settings.holdSeconds()));
            case ORBIT -> Math.max(1, settings.holdSeconds());
        };
        int[] ways = kind == KillCamSettings.Finale.ORBIT ? new int[]{1, -1} : new int[]{1};
        double from = angle;
        double best = -1;
        for (double up : RAISES) {
            for (int step = 0; step <= 6; step++) {
                for (int sign : step == 0 ? new int[]{1} : new int[]{1, -1}) {
                    double turned = from + sign * step * Math.toRadians(30);
                    for (int way : ways) {
                        // The worst moment of the path is what it is worth; one already worse than the best stops.
                        double score = 1;
                        for (double at = 0; at <= span + 1.0E-9 && score > best; at += SAMPLE) {
                            score = Math.min(score, framing(walls, centre, finaleSeat(turned, way, up, at), finaleSeen));
                        }
                        if (score > best + 0.05 || score >= GOOD) {
                            best = score;
                            angle = turned;
                            turn = way;
                            raise = up;
                            fromBlow = step == 0 && up == 0;
                        }
                        // Good enough near where the blow was seen beats a little better far round or high up.
                        if (score >= GOOD) return;
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private double progress(int tick) {
        double span = Math.max(1, timeline.death() - closeFrom);
        return Math.clamp((tick - closeFrom) / span, 0.0, 1.0);
    }

    private static double eased(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * A seat facing what it films, pulled in front of any wall between them,
     * and nearer still while one hides any of {@code seen}.
     * It snaps in at once, the frame a wall comes between, and eases back out
     * after, so an orbit passing a tree does not jump in and out with every log.
     */
    private Location looking(Location seat, Location at, List<Location> seen) {
        CameraWalls walls = this.walls;
        if (walls != null) {
            double wanted = seat.distance(at);
            double free = roomAt(walls, at, seat, seen);
            // A cut (another shot, or the chase handing over to the close-up) starts where it has room.
            boolean cut = wantedLast == null || wantedLast.getWorld() != seat.getWorld()
                    || wantedLast.distanceSquared(seat) > 4;
            wantedLast = seat.clone();
            room = cut ? free : Math.min(free, room + ROOM_BACK);
            if (room < wanted - 1.0E-3) {
                seat = at.clone().add(seat.toVector().subtract(at.toVector()).multiply(room / wanted));
            }
        }
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
