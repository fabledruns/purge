package net.fabledruns.purge.system;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.game.ArenaManager;
import net.fabledruns.purge.game.GameManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class PlayerManager implements Listener {

    private final PurgePlugin plugin;
    private final StateManager stateManager;

    private final Set<UUID> alivePlayers;
    private final Set<UUID> deadPlayers;
    private final Map<UUID, Long> combatTagExpiryMillis;
    private final Map<UUID, Integer> killStreaks;
    private final Set<UUID> bountyTargets;
    private final Map<UUID, LoggedBody> bodyByOwner;
    private final Map<UUID, UUID> ownerByBodyEntity;

    private ArenaManager arenaManager;

    public PlayerManager(PurgePlugin plugin, StateManager stateManager, GameManager gameManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;

        this.alivePlayers = ConcurrentHashMap.newKeySet();
        this.deadPlayers = ConcurrentHashMap.newKeySet();
        this.combatTagExpiryMillis = new ConcurrentHashMap<>();
        this.killStreaks = new ConcurrentHashMap<>();
        this.bountyTargets = ConcurrentHashMap.newKeySet();
        this.bodyByOwner = new ConcurrentHashMap<>();
        this.ownerByBodyEntity = new ConcurrentHashMap<>();

        loadPlayerState();
    }

    public void wire(ArenaManager arenaManager) {
        this.arenaManager = arenaManager;
    }

    public boolean isAlive(UUID playerId) {
        return alivePlayers.contains(playerId);
    }

    public int getAliveCount() {
        return alivePlayers.size();
    }

    public Set<UUID> getAlivePlayers() {
        return Set.copyOf(alivePlayers);
    }

    public void eliminatePlayer(UUID playerId, String reason, Player killer) {
        if (!alivePlayers.contains(playerId)) {
            return;
        }

        alivePlayers.remove(playerId);
        deadPlayers.add(playerId);
        killStreaks.remove(playerId);
        bountyTargets.remove(playerId);

        stateManager.setPlayerStates(alivePlayers, deadPlayers);
        stateManager.saveStateAsync();

        Player target = Bukkit.getPlayer(playerId);
        if (target != null && target.isOnline()) {
            target.setGameMode(GameMode.SPECTATOR);
            target.sendMessage("You are eliminated. " + reason);
        }

        if (killer != null && killer.getUniqueId() != playerId) {
            registerKill(killer.getUniqueId());
        }

        if (arenaManager != null) {
            arenaManager.onPlayerEliminated(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatTag(EntityDamageByEntityEvent event) {
        Player attacker = toPlayerDamager(event.getDamager());
        if (!(event.getEntity() instanceof Player victim) || attacker == null) {
            return;
        }

        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        long tagUntil = System.currentTimeMillis() + getCombatTagSeconds() * 1000L;
        combatTagExpiryMillis.put(attacker.getUniqueId(), tagUntil);
        combatTagExpiryMillis.put(victim.getUniqueId(), tagUntil);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();

        if (!alivePlayers.contains(victim.getUniqueId())) {
            return;
        }

        eliminatePlayer(victim.getUniqueId(), "Better luck next run.", killer);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!deadPlayers.contains(player.getUniqueId())) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> player.setGameMode(GameMode.SPECTATOR));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        LoggedBody body = bodyByOwner.remove(playerId);
        if (body != null) {
            Entity entity = Bukkit.getEntity(body.bodyEntityId());
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
            ownerByBodyEntity.remove(body.bodyEntityId());
        }

        if (deadPlayers.contains(playerId)) {
            player.setGameMode(GameMode.SPECTATOR);
            return;
        }

        if (!alivePlayers.contains(playerId)) {
            alivePlayers.add(playerId);
            stateManager.setPlayerStates(alivePlayers, deadPlayers);
            stateManager.saveStateAsync();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!alivePlayers.contains(playerId)) {
            return;
        }

        if (!isCombatTagged(playerId)) {
            return;
        }

        spawnCombatBody(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBodyDeath(EntityDeathEvent event) {
        UUID bodyEntityId = event.getEntity().getUniqueId();
        UUID ownerId = ownerByBodyEntity.remove(bodyEntityId);
        if (ownerId == null) {
            return;
        }

        bodyByOwner.remove(ownerId);

        Player ownerOnline = Bukkit.getPlayer(ownerId);
        if (ownerOnline != null && ownerOnline.isOnline()) {
            ownerOnline.setHealth(0.0D);
            return;
        }

        eliminatePlayer(ownerId, "Combat-logged and your body got folded.", null);
    }

    private void spawnCombatBody(Player owner) {
        if (bodyByOwner.containsKey(owner.getUniqueId())) {
            return;
        }

        Location at = owner.getLocation().clone();
        Zombie body = (Zombie) owner.getWorld().spawnEntity(at, EntityType.ZOMBIE);
        body.customName(Component.text(owner.getName() + " [Combat Body]"));
        body.setCustomNameVisible(true);
        body.setCanPickupItems(false);
        body.setShouldBurnInDay(false);
        body.setRemoveWhenFarAway(false);

        int timeoutTicks = Math.max(5, getLogoutGraceSeconds()) * 20;
        UUID ownerId = owner.getUniqueId();
        UUID bodyId = body.getUniqueId();

        bodyByOwner.put(ownerId, new LoggedBody(bodyId));
        ownerByBodyEntity.put(bodyId, ownerId);

        /*
         * Combat logging flow:
         * 1) Player logs during combat -> spawn vulnerable body.
         * 2) If body dies -> owner is eliminated immediately.
         * 3) If timeout hits and owner still offline -> also eliminated.
         *
         * Translation: rage quit does not unlock a magical vanish glitch.
         */
        Bukkit.getScheduler().runTaskLater(plugin, () -> resolveCombatBodyTimeout(ownerId, bodyId), timeoutTicks);
    }

    private void resolveCombatBodyTimeout(UUID ownerId, UUID bodyId) {
        LoggedBody body = bodyByOwner.get(ownerId);
        if (body == null || !body.bodyEntityId().equals(bodyId)) {
            return;
        }

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            return;
        }

        Entity entity = Bukkit.getEntity(bodyId);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }

        ownerByBodyEntity.remove(bodyId);
        bodyByOwner.remove(ownerId);
        eliminatePlayer(ownerId, "Combat-logged and timed out.", null);
    }

    private void registerKill(UUID killerId) {
        int newStreak = killStreaks.merge(killerId, 1, Integer::sum);
        int threshold = Math.max(2, plugin.getConfig().getInt("bounty.streak-threshold", 4));

        if (newStreak >= threshold && !bountyTargets.contains(killerId)) {
            bountyTargets.add(killerId);
            Player killer = Bukkit.getPlayer(killerId);
            String name = killer == null ? "Unknown" : killer.getName();
            Bukkit.broadcast(Component.text("Bounty placed on " + name + " [" + newStreak + " streak]."));
        }
    }

    private boolean isCombatTagged(UUID playerId) {
        long until = combatTagExpiryMillis.getOrDefault(playerId, 0L);
        return until > System.currentTimeMillis();
    }

    private int getCombatTagSeconds() {
        return Math.max(5, plugin.getConfig().getInt("combat.tag-seconds", 20));
    }

    private int getLogoutGraceSeconds() {
        return Math.max(10, plugin.getConfig().getInt("combat.logout-grace-seconds", 45));
    }

    private Player toPlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Arrow arrow) {
            ProjectileSource source = arrow.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    private void loadPlayerState() {
        Set<UUID> storedAlive = stateManager.getAlivePlayers();
        Set<UUID> storedDead = stateManager.getDeadPlayers();

        if (storedAlive.isEmpty() && storedDead.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                alivePlayers.add(player.getUniqueId());
            }
            stateManager.setPlayerStates(alivePlayers, deadPlayers);
            stateManager.saveStateAsync();
            return;
        }

        alivePlayers.addAll(storedAlive);
        deadPlayers.addAll(storedDead);

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID playerId = online.getUniqueId();
            if (deadPlayers.contains(playerId)) {
                online.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private record LoggedBody(UUID bodyEntityId) {
    }
}
