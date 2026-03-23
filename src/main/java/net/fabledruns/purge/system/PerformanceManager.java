package net.fabledruns.purge.system;

import java.util.ArrayList;
import java.util.List;
import net.fabledruns.purge.PurgePlugin;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.scheduler.BukkitTask;

public final class PerformanceManager {

    private final PurgePlugin plugin;

    private BukkitTask cleanupTask;

    private int cleanupIntervalSeconds;
    private int itemMaxAgeTicks;
    private int hostileMobCap;

    public PerformanceManager(PurgePlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        cleanupIntervalSeconds = Math.max(10, plugin.getConfig().getInt("anti-lag.cleanup-interval-seconds", 30));
        itemMaxAgeTicks = Math.max(20 * 30, plugin.getConfig().getInt("anti-lag.item-max-age-ticks", 20 * 120));
        hostileMobCap = Math.max(50, plugin.getConfig().getInt("anti-lag.hostile-mob-cap", 220));
    }

    public void start() {
        if (cleanupTask != null) {
            return;
        }

        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::runCleanup,
                cleanupIntervalSeconds * 20L,
                cleanupIntervalSeconds * 20L
        );
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    private void runCleanup() {
        for (World world : plugin.getServer().getWorlds()) {
            cleanupDroppedItems(world);
            cleanupHostileMobs(world);
        }
    }

    private void cleanupDroppedItems(World world) {
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (item.getTicksLived() > itemMaxAgeTicks) {
                item.remove();
            }
        }
    }

    private void cleanupHostileMobs(World world) {
        List<Monster> mobs = new ArrayList<>(world.getEntitiesByClass(Monster.class));
        if (mobs.size() <= hostileMobCap) {
            return;
        }

        int overflow = mobs.size() - hostileMobCap;
        for (int i = 0; i < overflow; i++) {
            mobs.get(i).remove();
        }
    }
}
