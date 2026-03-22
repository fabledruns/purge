package me.jehoshua.purge;

import me.jehoshua.purge.arena.ArenaManager;
import me.jehoshua.purge.command.PurgeCommand;
import me.jehoshua.purge.content.ContentManager;
import me.jehoshua.purge.game.GameManager;
import me.jehoshua.purge.performance.PerformanceManager;
import me.jehoshua.purge.player.PlayerManager;
import me.jehoshua.purge.state.StateManager;
import me.jehoshua.purge.team.TeamManager;
import me.jehoshua.purge.world.WorldManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PurgePlugin extends JavaPlugin {

    private StateManager stateManager;
    private GameManager gameManager;
    private PlayerManager playerManager;
    private TeamManager teamManager;
    private WorldManager worldManager;
    private ContentManager contentManager;
    private ArenaManager arenaManager;
    private PerformanceManager performanceManager;
    private PurgeCommand purgeCommand;

    public PurgePlugin() {
        super();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initializeManagers();
        registerListeners();
        registerCommands();
        startRuntimeTasks();

        getLogger().info("PurgePlugin enabled. Locked in and lowkey dangerous.");
    }

    @Override
    public void onDisable() {
        stopRuntimeTasks();

        if (stateManager != null) {
            stateManager.saveStateSync();
        }

        getLogger().info("PurgePlugin disabled.");
    }

    private void initializeManagers() {
        stateManager = new StateManager(this);
        stateManager.loadStateSync();

        gameManager = new GameManager(this, stateManager);
        playerManager = new PlayerManager(this, stateManager, gameManager);
        teamManager = new TeamManager(this, stateManager);
        worldManager = new WorldManager(this, gameManager, teamManager);
        contentManager = new ContentManager(this, stateManager, gameManager, teamManager);
        arenaManager = new ArenaManager(this, stateManager, gameManager, playerManager, teamManager);

        gameManager.wire(worldManager, contentManager, arenaManager);
        playerManager.wire(arenaManager);

        performanceManager = new PerformanceManager(this);
        purgeCommand = new PurgeCommand(
                this,
                gameManager,
                stateManager,
                playerManager,
                teamManager,
                worldManager,
                arenaManager,
                performanceManager
        );
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(playerManager, this);
        Bukkit.getPluginManager().registerEvents(teamManager, this);
        Bukkit.getPluginManager().registerEvents(worldManager, this);
        Bukkit.getPluginManager().registerEvents(contentManager, this);
    }

    private void registerCommands() {
        PluginCommand purgeRoot = getCommand("purge");
        PluginCommand teamRoot = getCommand("team");
        if (purgeRoot == null || teamRoot == null) {
            getLogger().severe("Required commands are missing in plugin.yml. Plugin bootstrap halted.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        purgeRoot.setExecutor(purgeCommand);
        purgeRoot.setTabCompleter(purgeCommand);

        teamRoot.setExecutor(purgeCommand);
        teamRoot.setTabCompleter(purgeCommand);
    }

    private void startRuntimeTasks() {
        gameManager.start();
        performanceManager.start();
    }

    private void stopRuntimeTasks() {
        if (performanceManager != null) {
            performanceManager.stop();
        }

        if (gameManager != null) {
            gameManager.stop();
        }
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public StateManager getStateManager() {
        return stateManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public TeamManager getTeamManager() {
        return teamManager;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }

    public ContentManager getContentManager() {
        return contentManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public PerformanceManager getPerformanceManager() {
        return performanceManager;
    }
}
