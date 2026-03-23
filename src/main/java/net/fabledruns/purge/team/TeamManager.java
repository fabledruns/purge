package net.fabledruns.purge.team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.system.StateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class TeamManager implements Listener {

    private static final List<NamedTextColor> TEAM_COLORS = List.of(
        NamedTextColor.RED,
        NamedTextColor.BLUE,
        NamedTextColor.GREEN,
        NamedTextColor.GOLD,
        NamedTextColor.AQUA,
        NamedTextColor.YELLOW,
        NamedTextColor.LIGHT_PURPLE,
        NamedTextColor.DARK_RED,
        NamedTextColor.DARK_BLUE,
        NamedTextColor.DARK_GREEN,
        NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_PURPLE,
        NamedTextColor.GRAY,
        NamedTextColor.DARK_GRAY,
        NamedTextColor.WHITE
    );

    private final PurgePlugin plugin;
    private final StateManager stateManager;

    private final Map<Integer, Team> teams;
    private final Map<UUID, Integer> playerToTeam;
    private int nextTeamId;

    public TeamManager(PurgePlugin plugin, StateManager stateManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.teams = new HashMap<>();
        this.playerToTeam = new HashMap<>();
        this.nextTeamId = 1;

        loadFromState();
        refreshOnlinePlayerVisuals();
    }

    public boolean createTeam(Player owner) {
        if (playerToTeam.containsKey(owner.getUniqueId())) {
            owner.sendMessage("Leave your current team first.");
            return false;
        }

        int teamId = nextTeamId++;
        Team team = new Team(teamId, pickColorIndexForTeamId(teamId));
        team.members.add(owner.getUniqueId());
        teams.put(teamId, team);
        playerToTeam.put(owner.getUniqueId(), teamId);
        persistTeams();

        updatePlayerVisuals(owner);
        owner.sendMessage("Created Team " + teamId + ".");
        return true;
    }

    public boolean joinTeam(Player player, int teamId) {
        Team team = teams.get(teamId);
        if (team == null) {
            player.sendMessage("Team not found.");
            return false;
        }

        if (playerToTeam.containsKey(player.getUniqueId())) {
            player.sendMessage("You are already in a team.");
            return false;
        }

        if (team.locked) {
            player.sendMessage("This team is locked.");
            return false;
        }

        int maxSize = Math.max(1, plugin.getConfig().getInt("teams.max-size", 4));
        if (team.members.size() >= maxSize) {
            player.sendMessage("Team is full.");
            return false;
        }

        team.members.add(player.getUniqueId());
        playerToTeam.put(player.getUniqueId(), teamId);
        persistTeams();

        updatePlayerVisuals(player);
        player.sendMessage("Joined Team " + teamId + ".");
        return true;
    }

    public boolean leaveTeam(Player player) {
        UUID playerId = player.getUniqueId();
        Integer teamId = playerToTeam.get(playerId);
        if (teamId == null) {
            player.sendMessage("You are not in a team.");
            return false;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            playerToTeam.remove(playerId);
            persistTeams();
            updatePlayerVisuals(player);
            return true;
        }

        team.members.remove(playerId);
        playerToTeam.remove(playerId);

        if (team.members.isEmpty()) {
            teams.remove(teamId);
        }

        persistTeams();
        updatePlayerVisuals(player);
        player.sendMessage("You left Team " + teamId + ".");
        return true;
    }

    public boolean setTeamLocked(Player actor, Integer teamId, boolean locked) {
        Integer resolvedTeamId = teamId;
        if (resolvedTeamId == null) {
            resolvedTeamId = playerToTeam.get(actor.getUniqueId());
        }
        if (resolvedTeamId == null) {
            actor.sendMessage("You are not in a team.");
            return false;
        }

        Team team = teams.get(resolvedTeamId);
        if (team == null) {
            actor.sendMessage("Team not found.");
            return false;
        }

        if (!team.members.contains(actor.getUniqueId()) && !actor.hasPermission("purge.admin")) {
            actor.sendMessage("You can only lock your own team.");
            return false;
        }

        team.locked = locked;
        persistTeams();

        actor.sendMessage("Team " + resolvedTeamId + (locked ? " locked." : " unlocked."));
        return true;
    }

    public Optional<Integer> getTeamId(UUID playerId) {
        return Optional.ofNullable(playerToTeam.get(playerId));
    }

    public Optional<String> getTeamName(UUID playerId) {
        Integer teamId = playerToTeam.get(playerId);
        if (teamId == null) {
            return Optional.empty();
        }
        return Optional.of("Team " + teamId);
    }

    public boolean sameTeam(UUID first, UUID second) {
        Integer t1 = playerToTeam.get(first);
        if (t1 == null) {
            return false;
        }
        return t1.equals(playerToTeam.get(second));
    }

    public boolean canFriendlyFire(UUID attacker, UUID victim, int currentDay) {
        if (currentDay >= 6) {
            return true;
        }

        boolean friendlyFireBeforeDay6 = plugin.getConfig().getBoolean("teams.friendly-fire-before-day6", false);
        if (friendlyFireBeforeDay6) {
            return true;
        }

        return !sameTeam(attacker, victim);
    }

    public Set<String> getAliveTeamKeys(Set<UUID> alivePlayers) {
        Set<String> aliveTeams = new HashSet<>();
        for (UUID playerId : alivePlayers) {
            Integer team = playerToTeam.get(playerId);
            if (team != null) {
                aliveTeams.add(String.valueOf(team));
            }
        }
        return aliveTeams;
    }

    public int getTeamCount() {
        return teams.size();
    }

    public Map<Integer, Set<UUID>> getTeamsSnapshot() {
        Map<Integer, Set<UUID>> snapshot = new HashMap<>();
        for (Map.Entry<Integer, Team> entry : teams.entrySet()) {
            snapshot.put(entry.getKey(), new HashSet<>(entry.getValue().members));
        }
        return snapshot;
    }

    public List<Integer> getActiveTeamIds() {
        List<Integer> ids = new ArrayList<>(teams.keySet());
        ids.sort(Integer::compareTo);
        return ids;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        updatePlayerVisuals(event.getPlayer());
    }

    private void loadFromState() {
        int maxTeamId = 0;
        int candidateNextId = Math.max(1, stateManager.getNextTeamId());
        boolean migrated = false;

        Map<String, StateManager.TeamState> stored = stateManager.getTeams();
        for (Map.Entry<String, StateManager.TeamState> entry : stored.entrySet()) {
            int teamId = parseTeamId(entry.getKey(), candidateNextId);
            if (teamId >= candidateNextId) {
                candidateNextId = teamId + 1;
            }
            if (!entry.getKey().equals(String.valueOf(teamId))) {
                migrated = true;
            }
            while (teams.containsKey(teamId)) {
                teamId = candidateNextId++;
                migrated = true;
            }

            int colorIndex = pickColorIndexForTeamId(teamId);
            if (normalizeColorIndex(entry.getValue().getColorIndex()) != colorIndex) {
                migrated = true;
            }

            Team team = new Team(teamId, colorIndex);
            team.locked = entry.getValue().isLocked();
            team.members.addAll(entry.getValue().getMembers());
            teams.put(teamId, team);
            maxTeamId = Math.max(maxTeamId, teamId);

            for (UUID playerId : team.members) {
                playerToTeam.put(playerId, teamId);
            }
        }

        this.nextTeamId = Math.max(Math.max(1, candidateNextId), maxTeamId + 1);
        stateManager.setNextTeamId(this.nextTeamId);
        if (migrated) {
            persistTeams();
        }
    }

    private void persistTeams() {
        Map<String, StateManager.TeamState> stored = new HashMap<>();
        for (Map.Entry<Integer, Team> entry : teams.entrySet()) {
            StateManager.TeamState teamState = new StateManager.TeamState();
            teamState.setLocked(entry.getValue().locked);
            teamState.setColorIndex(entry.getValue().colorIndex);
            teamState.setMembers(new HashSet<>(entry.getValue().members));
            stored.put(String.valueOf(entry.getKey()), teamState);
        }

        stateManager.setTeams(stored);
        stateManager.setNextTeamId(nextTeamId);
        stateManager.saveStateAsync();
    }

    private int parseTeamId(String raw, int fallback) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Convert legacy named teams into numeric IDs.
        }
        return fallback;
    }

    private int pickColorIndexForTeamId(int teamId) {
        return Math.floorMod(teamId - 1, TEAM_COLORS.size());
    }

    private int normalizeColorIndex(int index) {
        if (index < 0 || index >= TEAM_COLORS.size()) {
            return 0;
        }
        return index;
    }

    private void refreshOnlinePlayerVisuals() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            updatePlayerVisuals(online);
        }
    }

    private void updatePlayerVisuals(Player player) {
        Integer teamId = playerToTeam.get(player.getUniqueId());
        if (teamId == null) {
            Component plainName = Component.text(player.getName(), NamedTextColor.WHITE);
            player.displayName(plainName);
            player.playerListName(plainName);
            return;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            Component plainName = Component.text(player.getName(), NamedTextColor.WHITE);
            player.displayName(plainName);
            player.playerListName(plainName);
            return;
        }

        NamedTextColor teamColor = TEAM_COLORS.get(normalizeColorIndex(team.colorIndex));
        Component styledName = Component.text("[" + team.id + "] ", teamColor)
            .append(Component.text(player.getName(), NamedTextColor.WHITE));
        player.displayName(styledName);
        player.playerListName(styledName);
    }

    private static final class Team {
        private final int id;
        private final int colorIndex;
        private boolean locked;
        private final Set<UUID> members;

        private Team(int id, int colorIndex) {
            this.id = id;
            this.colorIndex = colorIndex;
            this.locked = false;
            this.members = new HashSet<>();
        }
    }
}
