package net.exylia.exyliaKillCam.killcam;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.api.ffa.event.FfaDeathEvent;
import net.exylia.lib.api.killeffect.event.KillEffectPlayEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Deaths, respawns and kill effects, handed to {@link KillCams}. */
public final class KillCamListener implements Listener {

    private final KillCams killCams;
    /** Read before anybody else handles the death: a minigame lets go of the player while it does. */
    private final Map<UUID, Moment> dying = new ConcurrentHashMap<>();
    /** Who ExyliaFFA credits, for a victim nobody landed the last blow on. */
    private final Map<UUID, Player> credited = new ConcurrentHashMap<>();

    public KillCamListener(@NotNull KillCams killCams) {
        this.killCams = killCams;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDying(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        dying.put(victim.getUniqueId(), new Moment(KillCams.isHeld(victim), victim.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFfaDeath(FfaDeathEvent event) {
        if (event.killer() != null) credited.put(event.victim().getUniqueId(), event.killer());
    }

    /**
     * Cancelled deaths count too: a minigame cancels the death it handles
     * itself. One of a player nobody held is a save (a totem, an item), not a death.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Moment moment = dying.remove(victim.getUniqueId());
        Player ffaKiller = credited.remove(victim.getUniqueId());
        if (moment == null || event.isCancelled() && !moment.held()) return;
        Entity killer = killer(event);
        if (killer == null) killer = ffaKiller;
        if (killer != null && !killer.equals(victim)) killCams.died(victim, killer, moment.where(), moment.held());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        killCams.respawned(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKillEffect(KillEffectPlayEvent event) {
        if (event.getVictim() instanceof Player victim) killCams.effectPlayed(victim.getUniqueId(), event.getEffectId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        dying.remove(event.getPlayer().getUniqueId());
        credited.remove(event.getPlayer().getUniqueId());
        killCams.forget(event.getPlayer());
    }

    /**
     * The player the server credits with the kill, arrows and pets included; a
     * mob only when mob deaths get a killcam.
     */
    private static Entity killer(PlayerDeathEvent event) {
        Player credited = event.getPlayer().getKiller();
        if (credited != null) return credited;
        if (!KillCamSettings.get().deaths().mobs()) return null;
        Entity causing = event.getDamageSource().getCausingEntity();
        return causing instanceof LivingEntity && !(causing instanceof Player) ? causing : null;
    }

    /** The victim as they were when the death began. */
    private record Moment(boolean held, Location where) {
    }
}
