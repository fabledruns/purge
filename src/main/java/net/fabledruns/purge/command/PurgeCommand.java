package net.fabledruns.purge.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.game.ArenaManager;
import net.fabledruns.purge.game.GameManager;
import net.fabledruns.purge.system.PerformanceManager;
import net.fabledruns.purge.system.PlayerManager;
import net.fabledruns.purge.system.StateManager;
import net.fabledruns.purge.system.WorldManager;
import net.fabledruns.purge.team.TeamManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class PurgeCommand implements CommandExecutor, TabCompleter {

    private final PurgePlugin plugin;
    private final GameManager gameManager;
    private final StateManager stateManager;
    private final PlayerManager playerManager;
    private final TeamManager teamManager;
    private final WorldManager worldManager;
    private final ArenaManager arenaManager;
    private final PerformanceManager performanceManager;

    public PurgeCommand(
            PurgePlugin plugin,
            GameManager gameManager,
            StateManager stateManager,
            PlayerManager playerManager,
            TeamManager teamManager,
            WorldManager worldManager,
            ArenaManager arenaManager,
            PerformanceManager performanceManager
    ) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.stateManager = stateManager;
        this.playerManager = playerManager;
        this.teamManager = teamManager;
        this.worldManager = worldManager;
        this.arenaManager = arenaManager;
        this.performanceManager = performanceManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("team")) {
            return handleTeamCommand(sender, args);
        }

        if (!sender.hasPermission("purge.admin")) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                gameManager.startEvent();
                sender.sendMessage("Event started.");
            }
            case "pause" -> {
                gameManager.pause();
                sender.sendMessage("Event paused.");
            }
            case "resume" -> {
                gameManager.resume();
                sender.sendMessage("Event resumed.");
            }
            case "next" -> {
                gameManager.forceNextDay();
                sender.sendMessage("Advanced to next day.");
            }
            case "setday" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /purge setday <1-7>");
                    return true;
                }

                try {
                    int day = Integer.parseInt(args[1]);
                    gameManager.setDay(day);
                    sender.sendMessage("Set day to " + day + ".");
                } catch (NumberFormatException exception) {
                    sender.sendMessage("Day must be a number.");
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                worldManager.reloadConfig();
                performanceManager.reloadConfig();
                if (plugin.getWeaponAbilityManager() != null) {
                    plugin.getWeaponAbilityManager().stop();
                    plugin.getWeaponAbilityManager().start();
                }
                sender.sendMessage("Config reloaded.");
            }
            case "status" -> sendStatus(sender);
            case "save" -> {
                stateManager.saveStateSync();
                sender.sendMessage("State saved.");
            }
            default -> sendUsage(sender);
        }

        return true;
    }

    private boolean handleTeamCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("purge.team")) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /team commands.");
            return true;
        }

        if (args.length == 0) {
            sendTeamUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length != 1) {
                    sender.sendMessage("Usage: /team create");
                    return true;
                }
                teamManager.createTeam(player);
            }
            case "join" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /team join <teamId>");
                    return true;
                }
                Integer teamId = parseTeamId(args[1]);
                if (teamId == null) {
                    sender.sendMessage("Team ID must be a positive number.");
                    return true;
                }
                teamManager.joinTeam(player, teamId);
            }
            case "leave" -> teamManager.leaveTeam(player);
            case "lock" -> {
                Integer teamId = resolveTargetTeamId(player, args);
                if (teamId == null) {
                    sender.sendMessage("Usage: /team lock <teamId> or /team lock");
                    return true;
                }
                teamManager.setTeamLocked(player, teamId, true);
            }
            case "unlock" -> {
                Integer teamId = resolveTargetTeamId(player, args);
                if (teamId == null) {
                    sender.sendMessage("Usage: /team unlock <teamId> or /team unlock");
                    return true;
                }
                teamManager.setTeamLocked(player, teamId, false);
            }
            case "info" -> {
                String current = teamManager.getTeamName(player.getUniqueId()).orElse(null);
                if (current == null) {
                    sender.sendMessage("You are not in a team.");
                } else {
                    sender.sendMessage("Current team: " + current);
                }
            }
            default -> sendTeamUsage(sender);
        }

        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Purge Admin Commands:");
        sender.sendMessage("/purge start");
        sender.sendMessage("/purge pause");
        sender.sendMessage("/purge resume");
        sender.sendMessage("/purge next");
        sender.sendMessage("/purge setday <1-7>");
        sender.sendMessage("/purge reload");
        sender.sendMessage("/purge status");
        sender.sendMessage("/purge save");
    }

    private void sendTeamUsage(CommandSender sender) {
        sender.sendMessage("Team Commands:");
        sender.sendMessage("/team create");
        sender.sendMessage("/team join <teamId>");
        sender.sendMessage("/team leave");
        sender.sendMessage("/team lock [teamId]");
        sender.sendMessage("/team unlock [teamId]");
        sender.sendMessage("/team info");
    }

    private Integer resolveTargetTeamId(Player player, String[] args) {
        if (args.length >= 2) {
            return parseTeamId(args[1]);
        }
        return teamManager.getTeamId(player.getUniqueId()).orElse(null);
    }

    private Integer parseTeamId(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed <= 0) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("--- Purge Status ---");
        sender.sendMessage("Day: " + gameManager.getCurrentDay());
        sender.sendMessage("Paused: " + gameManager.isPaused());
        sender.sendMessage("Remaining: " + gameManager.getRemainingSeconds() + "s");
        sender.sendMessage("Alive Players: " + playerManager.getAliveCount());
        sender.sendMessage("Teams: " + teamManager.getTeamCount());
        sender.sendMessage("Legendary Crafted: " + stateManager.getLegendaryCount() + "/4");
        sender.sendMessage("Arena Started: " + arenaManager.isStarted());
        sender.sendMessage("Arena Finished: " + arenaManager.isFinished());
        sender.sendMessage("Winner: " + (arenaManager.getWinnerTeam() == null ? "N/A" : arenaManager.getWinnerTeam()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("team")) {
            if (args.length == 1) {
                return filter(args[0], List.of("create", "join", "leave", "lock", "unlock", "info"));
            }

            if (args.length == 2 && (args[0].equalsIgnoreCase("join")
                    || args[0].equalsIgnoreCase("lock")
                    || args[0].equalsIgnoreCase("unlock"))) {
                return filter(args[1], teamManager.getActiveTeamIds().stream().map(String::valueOf).toList());
            }

            return List.of();
        }

        if (!sender.hasPermission("purge.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(args[0], List.of("start", "pause", "resume", "next", "setday", "reload", "status", "save"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("setday")) {
            return filter(args[1], List.of("1", "2", "3", "4", "5", "6", "7"));
        }

        return List.of();
    }

    private List<String> filter(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
