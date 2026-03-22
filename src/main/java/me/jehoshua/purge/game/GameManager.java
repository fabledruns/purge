package me.jehoshua.purge.game;

import java.util.concurrent.TimeUnit;
import me.jehoshua.purge.PurgePlugin;
import me.jehoshua.purge.arena.ArenaManager;
import me.jehoshua.purge.content.ContentManager;
import me.jehoshua.purge.state.StateManager;
import me.jehoshua.purge.world.WorldManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public final class GameManager {

    private final PurgePlugin plugin;
    private final StateManager stateManager;

    private WorldManager worldManager;
    private ContentManager contentManager;
    private ArenaManager arenaManager;

    private BukkitTask tickTask;
    private int currentDay;
    private long dayEndEpochMillis;
    private long pausedRemainingMillis;
    private boolean paused;

    public GameManager(PurgePlugin plugin, StateManager stateManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.currentDay = stateManager.getCurrentDay();
        this.dayEndEpochMillis = stateManager.getDayEndEpochMillis();
        this.paused = stateManager.isPaused();
        if (paused) {
            long now = System.currentTimeMillis();
            this.pausedRemainingMillis = dayEndEpochMillis > now ? dayEndEpochMillis - now : Math.max(0L, dayEndEpochMillis);
        } else {
            this.pausedRemainingMillis = 0L;
        }
    }

    public void wire(WorldManager worldManager, ContentManager contentManager, ArenaManager arenaManager) {
        this.worldManager = worldManager;
        this.contentManager = contentManager;
        this.arenaManager = arenaManager;
    }

    public void start() {
        if (tickTask != null) {
            return;
        }

        if (dayEndEpochMillis <= 0L) {
            dayEndEpochMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(getDayDurationSeconds(currentDay));
            persistDayClock();
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        notifySystems(currentDay);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        stateManager.saveStateSync();
    }

    public void startEvent() {
        long now = System.currentTimeMillis();
        paused = false;
        if (pausedRemainingMillis > 0L) {
            dayEndEpochMillis = now + pausedRemainingMillis;
            pausedRemainingMillis = 0L;
        } else if (dayEndEpochMillis <= 0L || dayEndEpochMillis <= now) {
            dayEndEpochMillis = now + TimeUnit.SECONDS.toMillis(getDayDurationSeconds(currentDay));
        }
        persistDayClock();
        broadcast("Purge event started on Day " + currentDay + ". Good luck, stay delusional and alive.");
    }

    public void pause() {
        if (paused) {
            return;
        }
        pausedRemainingMillis = Math.max(0L, dayEndEpochMillis - System.currentTimeMillis());
        paused = true;
        persistDayClock();
        broadcast("Purge timer paused by admin.");
    }

    public void resume() {
        if (!paused) {
            return;
        }

        long now = System.currentTimeMillis();
        long remaining = pausedRemainingMillis > 0L
                ? pausedRemainingMillis
                : Math.max(0L, dayEndEpochMillis - now);

        paused = false;
        dayEndEpochMillis = remaining > 0L
                ? now + remaining
                : now + TimeUnit.SECONDS.toMillis(getDayDurationSeconds(currentDay));
        pausedRemainingMillis = 0L;
        persistDayClock();
        broadcast("Purge timer resumed.");
    }

    public void forceNextDay() {
        if (currentDay >= 7) {
            broadcast("Already on Day 7. No next day exists.");
            return;
        }
        transitionToDay(currentDay + 1, true);
    }

    public void setDay(int day) {
        int clamped = Math.max(1, Math.min(7, day));
        transitionToDay(clamped, false);
    }

    public int getCurrentDay() {
        return currentDay;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isFullPurgeActive() {
        return currentDay >= 6;
    }

    public long getRemainingSeconds() {
        if (paused) {
            return Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(pausedRemainingMillis));
        }

        long remainingMs = dayEndEpochMillis - System.currentTimeMillis();
        return Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(remainingMs));
    }

    private void tick() {
        if (paused) {
            return;
        }

        if (System.currentTimeMillis() >= dayEndEpochMillis) {
            if (currentDay < 7) {
                transitionToDay(currentDay + 1, true);
            } else {
                paused = true;
                persistDayClock();
            }
        }
    }

    private void transitionToDay(int targetDay, boolean automatic) {
        int previousDay = currentDay;
        currentDay = Math.max(1, Math.min(7, targetDay));
        dayEndEpochMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(getDayDurationSeconds(currentDay));
        pausedRemainingMillis = 0L;

        /*
         * This is the entire day transition brain in one place.
         * No event bus maze, no manager ping-pong, just direct calls.
         * If this method breaks, everyone feels it immediately, so we keep it
         * centralized and painfully obvious for future-you at 3 AM.
         */
        notifySystems(currentDay);
        persistDayClock();

        String source = automatic ? "(auto)" : "(admin)";
        broadcast("Day " + previousDay + " -> Day " + currentDay + " " + source);
        broadcastDayFlavor(currentDay);
    }

    private void notifySystems(int day) {
        if (worldManager != null) {
            worldManager.onDayChanged(day);
        }
        if (contentManager != null) {
            contentManager.onDayChanged(day);
        }
        if (arenaManager != null) {
            arenaManager.onDayChanged(day);
        }
    }

    private int getDayDurationSeconds(int day) {
        String perDayPath = "days." + day + ".duration-seconds";
        int fallback = plugin.getConfig().getInt("days.default-duration-seconds", 3 * 60 * 60);
        return Math.max(60, plugin.getConfig().getInt(perDayPath, fallback));
    }

    private void persistDayClock() {
        stateManager.setCurrentDay(currentDay);
        stateManager.setDayEndEpochMillis(paused ? pausedRemainingMillis : dayEndEpochMillis);
        stateManager.setPaused(paused);
        stateManager.saveStateAsync();
    }

    private void broadcastDayFlavor(int day) {
        switch (day) {
            case 1 -> broadcast("Day 1: Gather up, scout structures, survive the early smoke.");
            case 2 -> broadcast("Day 2: Higher-tier structures unlocked. Zones are bigger. Drama is bigger.");
            case 3 -> broadcast("Day 3: Legendary crafting unlocked. Only 4 total, so craft fast or cry faster.");
            case 4 -> broadcast("Day 4: Nether unlocked as a full purge zone.");
            case 5 -> broadcast("Day 5: End unlocked. Final boss and giga loot are live.");
            case 6 -> broadcast("Day 6: THE PURGE. Rules deleted, map goes feral.");
            case 7 -> broadcast("Day 7: Final arena phase. Last team standing wins.");
            default -> {
            }
        }
    }

    private void broadcast(String text) {
        Bukkit.broadcast(Component.text(text));
    }
}
