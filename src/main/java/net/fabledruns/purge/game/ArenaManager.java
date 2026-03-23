package net.fabledruns.purge.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.system.PlayerManager;
import net.fabledruns.purge.system.StateManager;
import net.fabledruns.purge.team.TeamManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class ArenaManager {

    private final PurgePlugin plugin;
    private final StateManager stateManager;
    private final GameManager gameManager;
    private final PlayerManager playerManager;
    private final TeamManager teamManager;

    private boolean started;
    private boolean finished;
    private String winnerTeam;
    private BukkitTask borderTask;

    public ArenaManager(
            PurgePlugin plugin,
            StateManager stateManager,
            GameManager gameManager,
            PlayerManager playerManager,
            TeamManager teamManager
    ) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.gameManager = gameManager;
        this.playerManager = playerManager;
        this.teamManager = teamManager;

        StateManager.ArenaState arenaState = stateManager.getArenaState();
        this.started = arenaState.isStarted();
        this.finished = arenaState.isFinished();
        this.winnerTeam = arenaState.getWinnerTeam();
    }

    public void onDayChanged(int day) {
        if (day != 7) {
            return;
        }

        if (!started) {
            startArena();
            return;
        }

        if (!finished) {
            resumeArena();
        }
    }

    public void onPlayerEliminated(UUID playerId) {
        if (!started || finished) {
            return;
        }
        evaluateWinCondition();
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    public String getWinnerTeam() {
        return winnerTeam;
    }

    public void startArena() {
        if (started) {
            return;
        }

        started = true;
        finished = false;
        winnerTeam = null;

        List<Location> spawns = loadArenaSpawns();
        if (spawns.isEmpty()) {
            plugin.getLogger().warning("No arena.spawns configured. Using world spawn fallback.");
            World world = Bukkit.getWorlds().get(0);
            spawns = List.of(world.getSpawnLocation());
        }

        Map<String, List<UUID>> aliveByTeam = groupAlivePlayersByTeam();
        int index = 0;
        for (List<UUID> members : aliveByTeam.values()) {
            Location spawn = spawns.get(index % spawns.size());
            for (UUID memberId : members) {
                Player player = Bukkit.getPlayer(memberId);
                if (player != null && player.isOnline()) {
                    player.teleport(spawn);
                }
            }
            index++;
        }

        configureArenaBorder(spawns.get(0), false);
        persistArenaState();

        Bukkit.broadcast(Component.text("Day 7 arena started. Last team standing takes it all."));
        evaluateWinCondition();
    }

    private void resumeArena() {
        List<Location> spawns = loadArenaSpawns();
        Location center;
        if (!spawns.isEmpty()) {
            center = spawns.get(0);
        } else {
            World world = Bukkit.getWorlds().get(0);
            center = world.getSpawnLocation();
        }

        configureArenaBorder(center, true);
        enforceSpectatorState();
        evaluateWinCondition();
    }

    private Map<String, List<UUID>> groupAlivePlayersByTeam() {
        Map<String, List<UUID>> grouped = new HashMap<>();
        for (UUID playerId : playerManager.getAlivePlayers()) {
            String teamKey = teamManager.getTeamName(playerId).orElse("solo:" + playerId.toString().substring(0, 8));
            grouped.computeIfAbsent(teamKey, key -> new ArrayList<>()).add(playerId);
        }
        return grouped;
    }

    private List<Location> loadArenaSpawns() {
        List<Location> spawns = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("arena.spawns")) {
            Location parsed = parseLocation(raw);
            if (parsed != null) {
                spawns.add(parsed);
            }
        }
        return spawns;
    }

    private Location parseLocation(String raw) {
        String[] split = raw.split(",");
        if (split.length < 4) {
            return null;
        }

        World world = Bukkit.getWorld(split[0].trim());
        if (world == null) {
            return null;
        }

        try {
            double x = Double.parseDouble(split[1].trim());
            double y = Double.parseDouble(split[2].trim());
            double z = Double.parseDouble(split[3].trim());
            return new Location(world, x, y, z);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void configureArenaBorder(Location center, boolean resumeExistingBorder) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double configuredStartSize = Math.max(50.0D, plugin.getConfig().getDouble("arena.border.start-size", 300.0D));
        double endSize = Math.max(10.0D, plugin.getConfig().getDouble("arena.border.end-size", 30.0D));
        long shrinkSeconds = Math.max(10L, plugin.getConfig().getLong("arena.border.shrink-seconds", 900L));

        WorldBorder border = world.getWorldBorder();
        border.setCenter(center);

        double resolvedStartSize = configuredStartSize;
        if (resumeExistingBorder) {
            resolvedStartSize = Math.max(endSize, Math.min(configuredStartSize, border.getSize()));
        }
        final double startSize = resolvedStartSize;
        border.setSize(startSize);

        if (borderTask != null) {
            borderTask.cancel();
        }

        long totalTicks = shrinkSeconds * 20L;
        if (totalTicks <= 0L) {
            border.setSize(endSize);
            return;
        }

        if (startSize <= endSize) {
            border.setSize(endSize);
            return;
        }

        borderTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private long elapsedTicks = 0L;

            @Override
            public void run() {
                if (finished || elapsedTicks >= totalTicks) {
                    border.setSize(endSize);
                    if (borderTask != null) {
                        borderTask.cancel();
                        borderTask = null;
                    }
                    return;
                }

                double progress = (double) elapsedTicks / (double) totalTicks;
                double nextSize = startSize + (endSize - startSize) * progress;
                border.setSize(Math.max(endSize, nextSize));
                elapsedTicks += 20L;
            }
        }, 20L, 20L);
    }

    private void enforceSpectatorState() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!playerManager.isAlive(online.getUniqueId())) {
                online.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private void evaluateWinCondition() {
        /*
         * Day 7 win check in one spot so it cannot desync across managers.
         * We collapse alive players into team buckets every elimination and stop
         * instantly once one bucket is left. Clean, deterministic, no spaghetti.
         * Also yes, this is where dreams are deleted respectfully.
         */
        Set<UUID> alivePlayers = playerManager.getAlivePlayers();
        if (alivePlayers.isEmpty()) {
            finishArena("no-team");
            return;
        }

        Map<String, Integer> aliveTeams = new HashMap<>();
        for (UUID playerId : alivePlayers) {
            String key = teamManager.getTeamName(playerId).orElse("solo:" + playerId.toString().substring(0, 8));
            aliveTeams.merge(key, 1, Integer::sum);
        }

        if (aliveTeams.size() == 1) {
            String winningTeam = aliveTeams.keySet().iterator().next();
            finishArena(winningTeam);
        }
    }

    private void finishArena(String teamName) {
        if (finished) {
            return;
        }

        finished = true;
        winnerTeam = teamName;
        persistArenaState();

        if (borderTask != null) {
            borderTask.cancel();
            borderTask = null;
        }

        gameManager.pause();
        Bukkit.broadcast(Component.text("Arena finished. Winner: " + teamName));
    }

    private void persistArenaState() {
        StateManager.ArenaState state = new StateManager.ArenaState();
        state.setStarted(started);
        state.setFinished(finished);
        state.setWinnerTeam(winnerTeam);
        stateManager.setArenaState(state);
        stateManager.saveStateAsync();
    }
}
