package net.exylia.exyliaKillCam.killcam;

import net.exylia.exyliaKillCam.ExyliaKillCam;
import net.exylia.exyliaKillCam.Permissions;
import net.exylia.exyliaKillCam.config.KillCamMessages;
import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.api.killeffect.KillEffectService;
import net.exylia.lib.replay.BlackBoxSettings;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayBlackBox;
import net.exylia.lib.replay.ReplayHit;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.replay.ReplayViewer;
import net.exylia.lib.replay.Replays;
import net.exylia.lib.session.DeathDrops;
import net.exylia.lib.session.PluginSessions;
import net.exylia.lib.session.Sessions;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.PlayerStates;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every killcam: cut out of the black box when somebody dies, watched where it
 * happened through the killer's eyes until the body falls.
 *
 * <h2>Where it is watched</h2>
 * A killcam is watched seconds after the death, on the same ground: the viewer
 * is taken to the real place as a ghost nobody sees, the recorded fight is drawn
 * for them alone and whoever is really there now is hidden from them
 * ({@link ReplayViewer#openLive}). Nothing is laid down anywhere, so a capture
 * keeps no ground of its own, and a death kept for {@code /killcam} weighs only
 * what moved and what changed.
 *
 * <h2>The shot</h2>
 * The clip starts a few seconds before the death and is watched through the
 * killer's eyes. Then the {@link Director} takes the camera: a close-up that
 * slows into the killing blow, or follows the arrow that lands it, the blow
 * frozen with a title over it, and a last shot around the body while the
 * killer's kill effect plays again for the viewer alone. How it is filmed is the
 * server's style, with the killer's own over it when ExyliaKillEffect gives them
 * one. Then the viewer is put back exactly as they were, and told who killed
 * them and how.
 *
 * <h2>Other plugins</h2>
 * A death another plugin is in charge of — the player is held by a duel, an
 * event or a match, or something takes over what they drop — waits until that
 * plugin lets them go, and a player watching is held themselves, so nothing else
 * picks them up halfway.
 */
public final class KillCams {

    /** Seconds the recording keeps after the death: the fall and the puff. */
    private static final int AFTER_SECONDS = 2;

    /** A death is offered once, after the respawn or the release that follows it, this soon. */
    private static final long OFFER_MILLIS = 60_000L;

    /** A kill effect belongs to the death it played on when it is this close to it. */
    private static final long EFFECT_MILLIS = 15_000L;

    private static final String SETTING = "mode";

    private final ExyliaKillCam core;
    private final PluginSessions sessions;
    private final Map<UUID, Death> deaths = new ConcurrentHashMap<>();
    private final Set<UUID> unseen = ConcurrentHashMap.newKeySet();
    /** Died while another plugin held them: offered once it lets them go. */
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Effect> effects = new ConcurrentHashMap<>();
    private final Map<UUID, Watch> watching = new ConcurrentHashMap<>();
    /** Kills in a row, until the player dies. */
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();
    /** Who killed each player last, for a revenge. */
    private final Map<UUID, UUID> lastKilledBy = new ConcurrentHashMap<>();
    private volatile ReplayBlackBox box;

    public KillCams(@NotNull ExyliaKillCam core) {
        this.core = core;
        this.sessions = Sessions.of(core.getPlugin());
        Sessions.watch(core.getPlugin(), null, claim -> {
            if (!claim.plugin().equals(core.getPlugin().getName())) released(claim.player());
        });
    }

    // --------------------------------------------------------------- lifetime

    /** Opens this plugin's hold on the black box, with the settings as they are now. */
    public void open() {
        KillCamSettings settings = KillCamSettings.get();
        boolean hideVanished = settings.recording().hideVanished();
        box = Replays.of(core.getPlugin()).blackBox(BlackBoxSettings.defaults()
                .seconds(settings.killcam().secondsBefore() + AFTER_SECONDS + 1)
                .radius(settings.recording().radius())
                // Watched where it happened: the ground is the real one.
                .terrain(false)
                .hidden(player -> hideVanished && PlayerStates.isVanished(player)));
    }

    /**
     * Drops what can no longer be watched: a player who died once an hour ago
     * should not still be paying for it.
     */
    public void sweep() {
        long now = System.currentTimeMillis();
        deaths.entrySet().removeIf(entry -> !entry.getValue().isFresh(now, Math.max(OFFER_MILLIS, entry.getValue().keepMillis()))
                && !watching.containsKey(entry.getKey()));
        waiting.removeIf(id -> !deaths.containsKey(id));
        effects.values().removeIf(effect -> now - effect.at > EFFECT_MILLIS * 4);
    }

    /** Lets go of the box and sends everybody watching back. */
    public void close() {
        ReplayBlackBox held = box;
        box = null;
        if (held != null) held.close();
        ReplayViewer.release(core.getPlugin().getName());
        watching.clear();
    }

    /** The settings and messages changed: the box is opened again with the new ones. */
    public void reload() {
        ReplayBlackBox held = box;
        if (held != null) held.close();
        open();
    }

    // ----------------------------------------------------------------- deaths

    /**
     * Somebody died at somebody's hands: the seconds before are cut out of the
     * box, and the respawn, or the plugin holding them letting go, decides what
     * happens next.
     *
     * @param where      where they died
     * @param held       whether another plugin held them when the death began
     * @param projectile the projectile that landed the blow, if one did
     */
    public void died(@NotNull Player victim, @NotNull Entity killer, @NotNull Location where, boolean held,
                     @Nullable UUID projectile) {
        ReplayBlackBox recording = box;
        if (recording == null || !victim.hasPermission(Permissions.USE)) return;
        KillCamSettings settings = KillCamSettings.get();
        String world = where.getWorld().getName();
        if (settings.deaths().disabledWorlds().stream().anyMatch(world::equalsIgnoreCase)) return;
        if (watching.containsKey(victim.getUniqueId())) return;

        int before = Math.max(1, settings.killcam().secondsBefore());
        Death death = new Death(victim.getUniqueId(), killer.getUniqueId(), nameOf(killer), healthOf(killer),
                weaponOf(killer), distance(where, killer.getLocation()), System.currentTimeMillis(), Permissions.rewatchMillis(victim),
                projectile, victim.getUniqueId().equals(lastKilledBy.get(killer.getUniqueId())),
                streaks.getOrDefault(killer.getUniqueId(), 0) + 1, styleOf(killer, settings.style()),
                recording.capture(victim.getUniqueId(), before + 1, AFTER_SECONDS, false).thenApply(Replay::toBytes));
        // Nobody of them in the box (a vanished player, a server that just started): no killcam, and no noise.
        death.recording().exceptionally(nothing -> null);
        deaths.put(victim.getUniqueId(), death);

        if (held && settings.deaths().skipHeldPlayers()) {
            unseen.remove(victim.getUniqueId());
            waiting.add(victim.getUniqueId());
            // Let go while the death was being handled (left the arena on death): nobody will say so again.
            if (!isHeld(victim)) released(victim.getUniqueId());
            return;
        }
        waiting.remove(victim.getUniqueId());
        unseen.add(victim.getUniqueId());

        if (settings.killcam().autoRespawn() && mode(victim) == KillCamSettings.Mode.AUTO) {
            core.getTasks().runAtEntityLater(victim, 2L, () -> {
                if (victim.isDead()) victim.spigot().respawn();
            });
        }
    }

    /** The killer's style over the server's, when ExyliaKillEffect gives them one. */
    private KillCamSettings.Style styleOf(Entity killer, KillCamSettings.Style server) {
        if (!(killer instanceof Player)) return server;
        KillEffectService service = Bukkit.getServicesManager().load(KillEffectService.class);
        if (service == null) return server;
        try {
            return service.killCamStyle(killer.getUniqueId()).map(own -> server.over(own.settings())).orElse(server);
        } catch (AbstractMethodError | NoSuchMethodError older) {
            // ExyliaKillEffect built against an API before 3.8.0: no styles there.
            return server;
        }
    }

    /**
     * Counts a death for the streaks and revenges: the victim's streak ends, and
     * a player who killed them adds one and becomes who they can get back at.
     */
    public void tally(@NotNull Player victim, @Nullable Entity killer) {
        UUID id = victim.getUniqueId();
        streaks.remove(id);
        if (!(killer instanceof Player player) || player.equals(victim)) {
            lastKilledBy.remove(id);
            return;
        }
        streaks.merge(player.getUniqueId(), 1, Integer::sum);
        lastKilledBy.put(id, player.getUniqueId());
        // Got back at: a second kill on the same player is no revenge.
        lastKilledBy.remove(player.getUniqueId(), id);
    }

    /** They are back: the killcam starts, is offered, or nothing, as they chose. */
    public void respawned(@NotNull Player player) {
        if (unseen.remove(player.getUniqueId())) offer(player);
    }

    /**
     * Another plugin let go of somebody. A second later, once it has put them
     * back and nobody else took them, the death they had there is offered.
     */
    private void released(@NotNull UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !waiting.contains(id)) return;
        core.getTasks().runAtEntityLater(player, 20L, () -> {
            // Still held or dead: the next release tries again.
            if (!player.isOnline() || player.isDead() || isHeld(player) || !waiting.remove(id)) return;
            offer(player);
        });
    }

    /** The last death, as the player chose: watched, offered, or nothing. */
    private void offer(@NotNull Player player) {
        Death death = deaths.get(player.getUniqueId());
        if (death == null || !death.isFresh(System.currentTimeMillis(), OFFER_MILLIS)) return;
        switch (mode(player)) {
            case AUTO -> watch(player, death, false);
            case ASK -> core.getMessages().send(player, KillCamMessages.get().offer(),
                    Values.of().putText("killer", death.killerName()));
            case OFF -> {
                // They can still ask for it with /killcam.
            }
        }
    }

    /** A kill effect played on somebody: kept for the end of their killcam. */
    public void effectPlayed(@NotNull UUID victim, @NotNull String effectId) {
        effects.put(victim, new Effect(effectId, System.currentTimeMillis()));
    }

    /** Somebody left the server: nothing of theirs is kept. */
    public void forget(@NotNull Player player) {
        UUID id = player.getUniqueId();
        deaths.remove(id);
        unseen.remove(id);
        waiting.remove(id);
        effects.remove(id);
        streaks.remove(id);
        lastKilledBy.remove(id);
        lastKilledBy.values().removeIf(id::equals);
        skip(player);
    }

    /**
     * Whether another plugin is in charge of this player: a duel, an event, a
     * queue, a match, or anything that takes over what they drop.
     */
    static boolean isHeld(@NotNull Player player) {
        return !Sessions.isFree(player) || DeathDrops.claimedBy(player).isPresent()
                || ReplayViewer.of(player) != null;
    }

    // --------------------------------------------------------------- watching

    /** {@code /killcam}: the last death again, while it is still kept. */
    public void watchLast(@NotNull Player player) {
        Death death = deaths.get(player.getUniqueId());
        if (death == null || !death.isFresh(System.currentTimeMillis(), death.keepMillis())) {
            core.getMessages().send(player, KillCamMessages.get().noRecentDeath());
            return;
        }
        unseen.remove(player.getUniqueId());
        waiting.remove(player.getUniqueId());
        watch(player, death, true);
    }

    /**
     * Puts somebody in a killcam, where it happened.
     *
     * <p>The recording has to be there first, and the player is moved only
     * then, so a killcam that cannot be shown never takes anybody anywhere.
     *
     * @param asked whether they asked for it, and are told when it cannot be shown
     */
    private void watch(Player viewer, Death death, boolean asked) {
        KillCamMessages lines = KillCamMessages.get();
        if (!Replays.isSupported()) {
            if (asked) core.getMessages().send(viewer, lines.unsupported());
            return;
        }
        UUID id = viewer.getUniqueId();
        if (viewer.isDead() || watching.containsKey(id) || isHeld(viewer)) {
            if (asked) core.getMessages().send(viewer, lines.busy());
            return;
        }
        // Held while watching, so a duel request or a queue never picks them up halfway.
        if (sessions.claim(viewer, "killcam", () -> {
            skip(viewer);
            return true;
        }).isEmpty()) {
            if (asked) core.getMessages().send(viewer, lines.busy());
            return;
        }
        Watch watch = new Watch(death);
        watching.put(id, watch);
        viewer.closeInventory();

        death.replay()
                .thenAccept(replay -> core.getTasks().runAtEntity(viewer, () -> {
                    if (!viewer.isOnline() || watching.get(id) != watch) return;
                    ReplayViewer.openLive(core.getPlugin(), viewer, replay, death.victim())
                            .thenAccept(open -> core.getTasks().runAtEntity(viewer, () -> begin(viewer, watch, open)))
                            .exceptionally(failure -> failed(viewer, watch, failure, asked));
                }))
                .exceptionally(failure -> failed(viewer, watch, failure, asked));
    }

    /** There: through the killer's eyes, from the start of the clip. */
    private void begin(Player viewer, Watch watch, ReplayViewer open) {
        if (watching.get(viewer.getUniqueId()) != watch) {
            // Skipped while it was loading.
            open.leave();
            return;
        }
        KillCamSettings.Killcam settings = KillCamSettings.get().killcam();
        Death death = watch.death;
        Replay replay = open.playback().replay();
        watch.viewer = open;
        watch.timeline = Timeline.of(replay, death.victim(), settings.secondsBefore() * 20,
                (int) Math.round(settings.slowMotionSeconds() * 20));
        if (watch.timeline.start() > 0) open.playback().seek(watch.timeline.start());
        // The killer's own screen: everybody as late as their connection
        // showed them, so the crosshair is on the body they were aiming at.
        if (settings.firstPerson() && replay.actor(death.killer()) != null) {
            open.follow(death.killer(), ReplayPlayback.View.ATTACKER);
        }

        KillCamMessages lines = KillCamMessages.get();
        Values names = Values.of().putText("killer", death.killerName());
        watch.director = director(viewer, watch, open, replay, lines);
        viewer.showTitle(Title.title(core.getMessages().text(lines.title(), names).build(),
                core.getMessages().text(lines.subtitle(), names).build(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        core.getHotbar().show(viewer);
        open.hud(status -> core.getMessages().text(lines.hud(), Values.of()
                .putText("killer", death.killerName())
                .put("time", status.elapsed())
                .put("length", status.length())
                .put("bar", status.bar(16, lines.barDone(), lines.barLeft()))).build());
        // Retired too: a viewer who quits never runs the task, and the watch they
        // leave behind would keep their death, and its recording, for good.
        open.onLeave(() -> core.getTasks().runAtEntity(viewer, () -> ended(viewer, watch), () -> ended(viewer, watch)));
        if (settings.narrateHits()) {
            open.onMark(mark -> core.getTasks().runAtEntity(viewer, () -> narrate(viewer, replay, death, mark)));
        }
        watch.task = core.getTasks().runAtEntityTimer(viewer, 1L, 1L, () -> step(watch));
    }

    /** The camera from the close-up on, with the title this kill earned and the killer's line. */
    private Director director(Player viewer, Watch watch, ReplayViewer open, Replay replay, KillCamMessages lines) {
        Death death = watch.death;
        Director.Feat feat = Director.Feat.of(death.revenge(), death.killerHealth(), death.projectile() != null,
                death.distance(), oneHit(replay, death), death.streak());
        String title = switch (feat) {
            case REVENGE -> lines.titleRevenge();
            case CLUTCH -> lines.titleClutch();
            case LONG_SHOT -> lines.titleLongShot();
            case ONE_SHOT -> lines.titleOneShot();
            case STREAK -> lines.titleStreak();
            case ELIMINATED -> lines.titleEliminated();
        };
        Values figures = Values.of()
                .putText("killer", death.killerName())
                .putText("weapon", death.weapon())
                .put("health", hearts(death.killerHealth() * 2))
                .put("distance", String.format(Locale.ROOT, "%.0f", death.distance()))
                .put("streak", String.valueOf(death.streak()));
        KillCamSettings.Style style = death.style();
        return new Director(viewer, open, death, watch.timeline, KillCamSettings.get().killcam(),
                core.getMessages().text(title, figures).build(),
                core.getMessages().text(lines.line(), figures).build(),
                blocks -> core.getMessages().text(lines.caption(), Values.of().put("distance", String.valueOf(blocks))).build(),
                () -> core.getTasks().runAtEntityLater(viewer, 3L, () -> Director.sound(viewer, style.titleSound())),
                () -> {
                    Location body = open.playback().locationOf(death.victim());
                    if (body != null) core.getTasks().runAtLocation(body, () -> replayEffect(viewer, watch, body));
                });
    }

    /** Whether the blow was the only hit the victim took in the whole clip, and the killer's. */
    private static boolean oneHit(Replay replay, Death death) {
        int hits = 0;
        boolean killers = false;
        for (ReplayMark mark : replay.marks()) {
            ReplayHit hit = replay.hit(mark);
            if (hit == null || !hit.confirmed() || !hit.target().equals(death.victim())) continue;
            hits++;
            killers = hit.attacker().equals(death.killer());
        }
        return hits == 1 && killers;
    }

    /**
     * A hit the victim took, as damage only: no reach or aim numbers, which
     * would only invite players to accuse each other.
     */
    private void narrate(Player viewer, Replay replay, Death death, ReplayMark mark) {
        if (!viewer.isOnline() || watching.get(viewer.getUniqueId()) == null) return;
        ReplayHit hit = replay.hit(mark);
        if (hit == null || !hit.confirmed() || !hit.target().equals(death.victim())) return;
        ReplayActor attacker = replay.actor(hit.attacker());
        core.getMessages().send(viewer, KillCamMessages.get().hit(), Values.of()
                .putText("player", attacker == null ? death.killerName() : attacker.name())
                .put("damage", hearts(hit.damage()))
                .put("number", String.valueOf(replay.tally(hit).number())));
    }

    /** Health points as hearts: {@code 6.0} is {@code 3}, {@code 5.0} is {@code 2.5}. */
    static @NotNull String hearts(double health) {
        if (Double.isNaN(health)) return "?";
        String text = String.format(Locale.ROOT, "%.1f", health / 2.0);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** Every tick while it plays: the director moves the camera on, and says when it is over. */
    private void step(Watch watch) {
        ReplayViewer open = watch.viewer;
        if (open != null && watch.director != null && watch.director.step()) open.leave();
    }

    /** The killer's kill effect, once more, for the viewer alone. */
    private void replayEffect(Player viewer, Watch watch, Location body) {
        if (!KillCamSettings.get().killEffects().replay() || watch.viewer == null || !viewer.isOnline()) return;
        Effect effect = effects.get(watch.death.victim());
        if (effect == null || Math.abs(effect.at - watch.death.at()) > EFFECT_MILLIS) return;
        KillEffectService service = Bukkit.getServicesManager().load(KillEffectService.class);
        if (service != null) service.show(effect.id, body, viewer);
    }

    /** {@code /killcam skip}, the hotbar button, and somebody taking the player over. */
    public boolean skip(@NotNull Player viewer) {
        Watch watch = watching.get(viewer.getUniqueId());
        if (watch == null) return false;
        if (watch.viewer != null) {
            watch.viewer.leave();
        } else {
            // Still loading: begin() sees it gone and lets the stage go.
            watching.remove(viewer.getUniqueId(), watch);
            sessions.release(viewer);
        }
        return true;
    }

    /** Put back where they were: the controls go, and the line about who killed them comes. */
    private void ended(Player viewer, Watch watch) {
        if (watch.task != null) watch.task.cancel();
        if (!watching.remove(viewer.getUniqueId(), watch)) return;
        core.getHotbar().hide(viewer);
        sessions.release(viewer);
        if (!viewer.isOnline()) return;
        Death death = watch.death;
        core.getMessages().send(viewer, KillCamMessages.get().summary(), Values.of()
                .putText("killer", death.killerName())
                .put("health", String.format(Locale.ROOT, "%.1f", death.killerHealth()))
                .putText("weapon", death.weapon())
                .put("distance", String.format(Locale.ROOT, "%.1f", death.distance())));
    }

    private Void failed(Player viewer, Watch watch, Throwable failure, boolean asked) {
        core.getDebug().warn("A killcam could not be shown to " + viewer.getName() + ": " + failure);
        Runnable cleanUp = () -> {
            if (watch.task != null) watch.task.cancel();
            if (watching.remove(viewer.getUniqueId(), watch)) sessions.release(viewer);
            if (asked && viewer.isOnline()) core.getMessages().send(viewer, KillCamMessages.get().failed());
        };
        core.getTasks().runAtEntity(viewer, cleanUp, cleanUp);
        return null;
    }

    // ------------------------------------------------------------------ modes

    /** What this player gets after dying. */
    public @NotNull KillCamSettings.Mode mode(@NotNull Player player) {
        String value = net.exylia.lib.settings.Settings.value(player, core.getPlugin(), SETTING);
        try {
            return value == null ? KillCamSettings.get().killcam().defaultMode()
                    : KillCamSettings.Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return KillCamSettings.get().killcam().defaultMode();
        }
    }

    /** {@code /killcam mode}. */
    public boolean mode(@NotNull Player player, @NotNull KillCamSettings.Mode mode) {
        return net.exylia.lib.settings.Settings.set(player, core.getPlugin(), SETTING,
                mode.name().toLowerCase(Locale.ROOT));
    }

    /** The key {@code /settings} lists this plugin's choice under. */
    public static @NotNull String settingKey() {
        return SETTING;
    }

    // ---------------------------------------------------------------- figures

    private static String nameOf(Entity killer) {
        if (killer instanceof Player player) return player.getName();
        Component custom = killer.customName();
        if (custom != null) return PlainTextComponentSerializer.plainText().serialize(custom);
        String type = killer.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    private static double healthOf(Entity killer) {
        return killer instanceof LivingEntity living ? Math.max(0, living.getHealth()) / 2.0 : 0;
    }

    private static String weaponOf(Entity killer) {
        EntityEquipment equipment = killer instanceof LivingEntity living ? living.getEquipment() : null;
        ItemStack held = equipment == null ? null : equipment.getItemInMainHand();
        if (held == null || held.getType().isAir()) return KillCamMessages.get().fists();
        return PlainTextComponentSerializer.plainText().serialize(net.exylia.lib.platform.ItemNames.of(held));
    }

    private static double distance(Location a, Location b) {
        return a.getWorld() == b.getWorld() ? a.distance(b) : 0;
    }

    /** A kill effect that played on somebody, and when. */
    private record Effect(String id, long at) {
    }

    /** One player watching one killcam. Touched on the viewer's thread only. */
    private static final class Watch {
        private final Death death;
        private volatile ReplayViewer viewer;
        private Timeline timeline;
        private Director director;
        private TaskHandle task;

        private Watch(Death death) {
            this.death = death;
        }
    }
}
