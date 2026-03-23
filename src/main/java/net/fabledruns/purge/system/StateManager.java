package net.fabledruns.purge.system;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabledruns.purge.PurgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class StateManager {

    private final PurgePlugin plugin;
    private final File stateFile;
    private final Object lock;

    private StateData state;

    public StateManager(PurgePlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
        this.lock = new Object();
        this.state = new StateData();
    }

    public void loadStateSync() {
        synchronized (lock) {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder. State file may fail to save.");
            }

            if (!stateFile.exists()) {
                state = new StateData();
                return;
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
            StateData loaded = new StateData();

            loaded.currentDay = clampDay(yaml.getInt("day.current", 1));
            loaded.dayEndEpochMillis = Math.max(0L, yaml.getLong("day.end-epoch-ms", 0L));
            loaded.paused = yaml.getBoolean("day.paused", false);
            loaded.legendaryCount = Math.max(0, yaml.getInt("legendary.count", 0));
            for (String rawId : yaml.getStringList("legendary.crafted-ids")) {
                if (rawId == null || rawId.isBlank()) {
                    continue;
                }
                loaded.craftedLegendaryIds.add(rawId.toLowerCase(Locale.ROOT));
            }
            loaded.legendaryCount = Math.max(loaded.legendaryCount, loaded.craftedLegendaryIds.size());
            loaded.nextTeamId = Math.max(1, yaml.getInt("teams-meta.next-id", 1));

            for (String raw : yaml.getStringList("players.alive")) {
                UUID id = safeUuid(raw);
                if (id != null) {
                    loaded.alivePlayers.add(id);
                }
            }

            for (String raw : yaml.getStringList("players.dead")) {
                UUID id = safeUuid(raw);
                if (id != null) {
                    loaded.deadPlayers.add(id);
                }
            }

            ConfigurationSection teamsSection = yaml.getConfigurationSection("teams");
            if (teamsSection != null) {
                for (String teamName : teamsSection.getKeys(false)) {
                    ConfigurationSection section = teamsSection.getConfigurationSection(teamName);
                    if (section == null) {
                        continue;
                    }

                    TeamState teamState = new TeamState();
                    teamState.locked = section.getBoolean("locked", false);
                    teamState.colorIndex = Math.max(0, section.getInt("color-index", 0));
                    for (String raw : section.getStringList("members")) {
                        UUID id = safeUuid(raw);
                        if (id != null) {
                            teamState.members.add(id);
                        }
                    }

                    loaded.teams.put(teamName.toLowerCase(Locale.ROOT), teamState);
                }
            }

            loaded.arena.started = yaml.getBoolean("arena.started", false);
            loaded.arena.finished = yaml.getBoolean("arena.finished", false);
            loaded.arena.winnerTeam = yaml.getString("arena.winner-team", null);

            state = loaded;
        }
    }

    public void saveStateSync() {
        StateData snapshot = copyState();
        writeState(snapshot);
    }

    public void saveStateAsync() {
        StateData snapshot = copyState();
        if (!plugin.isEnabled()) {
            writeState(snapshot);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeState(snapshot));
    }

    public int getCurrentDay() {
        synchronized (lock) {
            return state.currentDay;
        }
    }

    public void setCurrentDay(int day) {
        synchronized (lock) {
            state.currentDay = clampDay(day);
        }
    }

    public long getDayEndEpochMillis() {
        synchronized (lock) {
            return state.dayEndEpochMillis;
        }
    }

    public void setDayEndEpochMillis(long epochMillis) {
        synchronized (lock) {
            state.dayEndEpochMillis = Math.max(0L, epochMillis);
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return state.paused;
        }
    }

    public void setPaused(boolean paused) {
        synchronized (lock) {
            state.paused = paused;
        }
    }

    public int getLegendaryCount() {
        synchronized (lock) {
            return state.legendaryCount;
        }
    }

    public void setLegendaryCount(int count) {
        synchronized (lock) {
            state.legendaryCount = Math.max(0, count);
        }
    }

    public Set<String> getCraftedLegendaryIds() {
        synchronized (lock) {
            return new HashSet<>(state.craftedLegendaryIds);
        }
    }

    public boolean isLegendaryCrafted(String weaponId) {
        if (weaponId == null || weaponId.isBlank()) {
            return false;
        }

        synchronized (lock) {
            return state.craftedLegendaryIds.contains(weaponId.toLowerCase(Locale.ROOT));
        }
    }

    public void markLegendaryCrafted(String weaponId) {
        if (weaponId == null || weaponId.isBlank()) {
            return;
        }

        synchronized (lock) {
            state.craftedLegendaryIds.add(weaponId.toLowerCase(Locale.ROOT));
            state.legendaryCount = state.craftedLegendaryIds.size();
        }
    }

    public int getNextTeamId() {
        synchronized (lock) {
            return state.nextTeamId;
        }
    }

    public void setNextTeamId(int nextTeamId) {
        synchronized (lock) {
            state.nextTeamId = Math.max(1, nextTeamId);
        }
    }

    public Set<UUID> getAlivePlayers() {
        synchronized (lock) {
            return new HashSet<>(state.alivePlayers);
        }
    }

    public Set<UUID> getDeadPlayers() {
        synchronized (lock) {
            return new HashSet<>(state.deadPlayers);
        }
    }

    public void setPlayerAlive(UUID playerId, boolean alive) {
        synchronized (lock) {
            if (alive) {
                state.alivePlayers.add(playerId);
                state.deadPlayers.remove(playerId);
            } else {
                state.deadPlayers.add(playerId);
                state.alivePlayers.remove(playerId);
            }
        }
    }

    public void setPlayerStates(Set<UUID> alivePlayers, Set<UUID> deadPlayers) {
        synchronized (lock) {
            state.alivePlayers.clear();
            state.alivePlayers.addAll(alivePlayers);

            state.deadPlayers.clear();
            state.deadPlayers.addAll(deadPlayers);
        }
    }

    public Map<String, TeamState> getTeams() {
        synchronized (lock) {
            Map<String, TeamState> copy = new HashMap<>();
            for (Map.Entry<String, TeamState> entry : state.teams.entrySet()) {
                TeamState teamCopy = new TeamState();
                teamCopy.locked = entry.getValue().locked;
                teamCopy.colorIndex = entry.getValue().colorIndex;
                teamCopy.members.addAll(entry.getValue().members);
                copy.put(entry.getKey(), teamCopy);
            }
            return copy;
        }
    }

    public void setTeams(Map<String, TeamState> teams) {
        synchronized (lock) {
            state.teams.clear();
            for (Map.Entry<String, TeamState> entry : teams.entrySet()) {
                TeamState teamCopy = new TeamState();
                teamCopy.locked = entry.getValue().locked;
                teamCopy.colorIndex = entry.getValue().colorIndex;
                teamCopy.members.addAll(entry.getValue().members);
                state.teams.put(entry.getKey().toLowerCase(Locale.ROOT), teamCopy);
            }
        }
    }

    public ArenaState getArenaState() {
        synchronized (lock) {
            ArenaState arena = new ArenaState();
            arena.started = state.arena.started;
            arena.finished = state.arena.finished;
            arena.winnerTeam = state.arena.winnerTeam;
            return arena;
        }
    }

    public void setArenaState(ArenaState arenaState) {
        synchronized (lock) {
            state.arena.started = arenaState.started;
            state.arena.finished = arenaState.finished;
            state.arena.winnerTeam = arenaState.winnerTeam;
        }
    }

    private StateData copyState() {
        synchronized (lock) {
            StateData copy = new StateData();
            copy.currentDay = state.currentDay;
            copy.dayEndEpochMillis = state.dayEndEpochMillis;
            copy.paused = state.paused;
            copy.legendaryCount = state.legendaryCount;
            copy.craftedLegendaryIds.addAll(state.craftedLegendaryIds);
            copy.nextTeamId = state.nextTeamId;

            copy.alivePlayers.addAll(state.alivePlayers);
            copy.deadPlayers.addAll(state.deadPlayers);

            for (Map.Entry<String, TeamState> entry : state.teams.entrySet()) {
                TeamState teamCopy = new TeamState();
                teamCopy.locked = entry.getValue().locked;
                teamCopy.colorIndex = entry.getValue().colorIndex;
                teamCopy.members.addAll(entry.getValue().members);
                copy.teams.put(entry.getKey(), teamCopy);
            }

            copy.arena.started = state.arena.started;
            copy.arena.finished = state.arena.finished;
            copy.arena.winnerTeam = state.arena.winnerTeam;
            return copy;
        }
    }

    private void writeState(StateData snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();

        yaml.set("day.current", snapshot.currentDay);
        yaml.set("day.end-epoch-ms", snapshot.dayEndEpochMillis);
        yaml.set("day.paused", snapshot.paused);
        yaml.set("legendary.count", snapshot.legendaryCount);
        yaml.set("legendary.crafted-ids", snapshot.craftedLegendaryIds.stream().sorted().toList());
        yaml.set("teams-meta.next-id", snapshot.nextTeamId);

        yaml.set("players.alive", snapshot.alivePlayers.stream().map(UUID::toString).toList());
        yaml.set("players.dead", snapshot.deadPlayers.stream().map(UUID::toString).toList());

        for (Map.Entry<String, TeamState> entry : snapshot.teams.entrySet()) {
            String basePath = "teams." + entry.getKey();
            yaml.set(basePath + ".locked", entry.getValue().locked);
            yaml.set(basePath + ".color-index", entry.getValue().colorIndex);
            yaml.set(basePath + ".members", entry.getValue().members.stream().map(UUID::toString).toList());
        }

        yaml.set("arena.started", snapshot.arena.started);
        yaml.set("arena.finished", snapshot.arena.finished);
        yaml.set("arena.winner-team", snapshot.arena.winnerTeam);

        try {
            yaml.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to save state.yml: " + exception.getMessage());
        }
    }

    private int clampDay(int day) {
        return Math.max(1, Math.min(7, day));
    }

    private UUID safeUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static final class TeamState {
        private boolean locked;
        private int colorIndex;
        private final Set<UUID> members;

        public TeamState() {
            this.locked = false;
            this.colorIndex = 0;
            this.members = new HashSet<>();
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }

        public int getColorIndex() {
            return colorIndex;
        }

        public void setColorIndex(int colorIndex) {
            this.colorIndex = Math.max(0, colorIndex);
        }

        public Set<UUID> getMembers() {
            return Collections.unmodifiableSet(members);
        }

        public void setMembers(Set<UUID> members) {
            this.members.clear();
            this.members.addAll(members);
        }
    }

    public static final class ArenaState {
        private boolean started;
        private boolean finished;
        private String winnerTeam;

        public boolean isStarted() {
            return started;
        }

        public void setStarted(boolean started) {
            this.started = started;
        }

        public boolean isFinished() {
            return finished;
        }

        public void setFinished(boolean finished) {
            this.finished = finished;
        }

        public String getWinnerTeam() {
            return winnerTeam;
        }

        public void setWinnerTeam(String winnerTeam) {
            this.winnerTeam = winnerTeam;
        }
    }

    private static final class StateData {
        private int currentDay = 1;
        private long dayEndEpochMillis = 0L;
        private boolean paused = true;
        private int legendaryCount = 0;
        private final Set<String> craftedLegendaryIds = new HashSet<>();
        private int nextTeamId = 1;

        private final Set<UUID> alivePlayers = new HashSet<>();
        private final Set<UUID> deadPlayers = new HashSet<>();
        private final Map<String, TeamState> teams = new HashMap<>();
        private final ArenaState arena = new ArenaState();
    }
}
