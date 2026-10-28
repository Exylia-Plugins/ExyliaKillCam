package net.exylia.exyliaKillCam.killcam;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import net.exylia.exyliaKillCam.config.KillCamSettings;
import net.exylia.lib.api.killeffect.event.KillEffectPlayEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** Deaths, respawns and kill effects, handed to {@link KillCams}. */
public final class KillCamListener implements Listener {

    private final KillCams killCams;

    public KillCamListener(@NotNull KillCams killCams) {
        this.killCams = killCams;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Entity killer = killer(event);
        if (killer != null && !killer.equals(victim)) killCams.died(victim, killer);
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
}
