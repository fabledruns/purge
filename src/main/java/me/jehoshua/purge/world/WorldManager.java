package me.jehoshua.purge.world;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.jehoshua.purge.PurgePlugin;
import me.jehoshua.purge.game.GameManager;
import me.jehoshua.purge.team.TeamManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class WorldManager implements Listener {

    private final PurgePlugin plugin;
    private final GameManager gameManager;
    private final TeamManager teamManager;

    private final Map<Integer, Set<String>> zoneIdsByDay;
    private final Map<Long, CacheEntry> zoneCheckCache;
    private final Map<UUID, Long> warnCooldown;
    private final WorldGuardBridge worldGuardBridge;

    private boolean netherUnlocked;
    private boolean endUnlocked;
    private boolean globalPurge;

    public WorldManager(PurgePlugin plugin, GameManager gameManager, TeamManager teamManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.teamManager = teamManager;
        this.zoneIdsByDay = new HashMap<>();
        this.zoneCheckCache = new ConcurrentHashMap<>();
        this.warnCooldown = new ConcurrentHashMap<>();
        this.worldGuardBridge = new WorldGuardBridge(plugin);

        reloadConfig();
        onDayChanged(gameManager.getCurrentDay());
    }

    public void reloadConfig() {
        zoneIdsByDay.clear();
        for (int day = 1; day <= 5; day++) {
            Set<String> ids = new HashSet<>();
            for (String raw : plugin.getConfig().getStringList("worldguard.zones.day" + day)) {
                ids.add(raw.toLowerCase(Locale.ROOT));
            }
            zoneIdsByDay.put(day, ids);
        }
        zoneCheckCache.clear();
    }

    public void onDayChanged(int day) {
        netherUnlocked = day >= 4;
        endUnlocked = day >= 5;
        globalPurge = day >= 6;
        zoneCheckCache.clear();

        if (day == 4) {
            Bukkit.broadcast(Component.text("Nether unlocked. The map just got worse in the best way."));
        }
        if (day == 5) {
            Bukkit.broadcast(Component.text("End unlocked. Time to lock in."));
        }
    }

    public boolean isPvpAllowed(Location location) {
        if (globalPurge) {
            return true;
        }

        int day = gameManager.getCurrentDay();
        if (day <= 5) {
            return isInAllowedZone(location, day);
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        Player attacker = resolveDamager(event.getDamager());
        if (!(event.getEntity() instanceof Player victim) || attacker == null) {
            return;
        }

        if (!isPvpAllowed(victim.getLocation())) {
            event.setCancelled(true);
            sendRuleWarning(attacker, "PvP is only enabled in purge zones right now.");
            return;
        }

        if (!teamManager.canFriendlyFire(attacker.getUniqueId(), victim.getUniqueId(), gameManager.getCurrentDay())) {
            event.setCancelled(true);
            sendRuleWarning(attacker, "Friendly fire is disabled before Day 6.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        World destination = event.getTo() == null ? null : event.getTo().getWorld();
        if (destination == null) {
            return;
        }

        if (destination.getEnvironment() == World.Environment.NETHER && !netherUnlocked) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Nether unlocks on Day 4.");
        } else if (destination.getEnvironment() == World.Environment.THE_END && !endUnlocked) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("End unlocks on Day 5.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }

        World destination = event.getTo() == null ? null : event.getTo().getWorld();
        if (destination == null) {
            return;
        }

        if (destination.getEnvironment() == World.Environment.NETHER && !netherUnlocked) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Nether unlocks on Day 4.");
        } else if (destination.getEnvironment() == World.Environment.THE_END && !endUnlocked) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("End unlocks on Day 5.");
        }
    }

    private boolean isInAllowedZone(Location location, int day) {
        if (!worldGuardBridge.available()) {
            return false;
        }

        Set<String> zoneIds = getExpandedZones(day);
        if (zoneIds.isEmpty()) {
            return false;
        }

        long key = cacheKey(location, day);
        long now = System.currentTimeMillis();

        CacheEntry cached = zoneCheckCache.get(key);
        if (cached != null && cached.expireAtMillis() > now) {
            return cached.inside();
        }

        boolean inside = worldGuardBridge.isInAnyRegion(location, zoneIds);
        zoneCheckCache.put(key, new CacheEntry(inside, now + 1500L));
        return inside;
    }

    private Set<String> getExpandedZones(int day) {
        Set<String> ids = new HashSet<>();
        for (int d = 1; d <= Math.min(day, 5); d++) {
            ids.addAll(zoneIdsByDay.getOrDefault(d, Set.of()));
        }
        return ids;
    }

    private long cacheKey(Location location, int day) {
        long worldHash = location.getWorld() == null ? 0L : location.getWorld().getUID().getLeastSignificantBits();
        long x = location.getBlockX() & 0x3FFFFFFL;
        long y = location.getBlockY() & 0xFFFL;
        long z = location.getBlockZ() & 0x3FFFFFFL;
        return worldHash ^ (x << 38) ^ (z << 12) ^ y ^ ((long) day << 58);
    }

    private Player resolveDamager(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    private void sendRuleWarning(Player player, String text) {
        long now = System.currentTimeMillis();
        long lastSent = warnCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastSent < 1000L) {
            return;
        }

        warnCooldown.put(player.getUniqueId(), now);
        player.sendMessage(text);
    }

    private record CacheEntry(boolean inside, long expireAtMillis) {
    }

    private static final class WorldGuardBridge {

        private final PurgePlugin plugin;
        private final boolean available;

        private Object regionContainer;
        private Method getRegionManagerMethod;
        private Method adaptWorldMethod;
        private Method vectorAtMethod;
        private Method getApplicableRegionsMethod;
        private Method getRegionIdMethod;

        private WorldGuardBridge(PurgePlugin plugin) {
            this.plugin = plugin;
            this.available = initialize();
        }

        private boolean initialize() {
            try {
                Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
                Object platform = worldGuard.getClass().getMethod("getPlatform").invoke(worldGuard);
                regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

                Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                adaptWorldMethod = bukkitAdapterClass.getMethod("adapt", World.class);

                Class<?> worldEditWorldClass = Class.forName("com.sk89q.worldedit.world.World");
                Class<?> blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");

                getRegionManagerMethod = regionContainer.getClass().getMethod("get", worldEditWorldClass);
                vectorAtMethod = blockVectorClass.getMethod("at", int.class, int.class, int.class);

                Class<?> regionManagerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
                getApplicableRegionsMethod = regionManagerClass.getMethod("getApplicableRegions", blockVectorClass);

                Class<?> protectedRegionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
                getRegionIdMethod = protectedRegionClass.getMethod("getId");
                return true;
            } catch (Exception exception) {
                plugin.getLogger().warning("WorldGuard not detected or failed to hook. Early-day zone PvP checks will fail closed until Day 6.");
                return false;
            }
        }

        private boolean available() {
            return available;
        }

        private boolean isInAnyRegion(Location location, Set<String> regionIds) {
            if (!available || location.getWorld() == null) {
                return false;
            }

            try {
                Object worldEditWorld = adaptWorldMethod.invoke(null, location.getWorld());
                Object regionManager = getRegionManagerMethod.invoke(regionContainer, worldEditWorld);
                if (regionManager == null) {
                    return false;
                }

                Object vector = vectorAtMethod.invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ());
                Object applicableRegions = getApplicableRegionsMethod.invoke(regionManager, vector);
                if (!(applicableRegions instanceof Iterable<?> iterable)) {
                    return false;
                }

                for (Object region : iterable) {
                    String id = ((String) getRegionIdMethod.invoke(region)).toLowerCase(Locale.ROOT);
                    if (regionIds.contains(id)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                return false;
            }

            return false;
        }
    }
}
