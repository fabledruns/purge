package net.fabledruns.purge.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.legendary.LegendaryWeapon;
import net.fabledruns.purge.legendary.LegendaryWeaponRegistry;
import net.fabledruns.purge.legendary.weapons.DragonWingsWeapon;
import net.fabledruns.purge.legendary.weapons.StoneFallWeapon;
import net.fabledruns.purge.legendary.weapons.VoidSnareWeapon;
import net.fabledruns.purge.legendary.weapons.WitherBoneBladeWeapon;
import net.fabledruns.purge.system.StateManager;
import net.fabledruns.purge.team.TeamManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;

public final class ContentManager implements Listener {

    public static final String WITHER_BONE_BLADE_ID = WitherBoneBladeWeapon.ID;
    public static final String DRAGON_WINGS_ID = DragonWingsWeapon.ID;
    public static final String STONE_FALL_ID = StoneFallWeapon.ID;
    public static final String VOID_SNARE_ID = VoidSnareWeapon.ID;
    private static final long LEGENDARY_LOCK_MESSAGE_COOLDOWN_MS = 2000L;

    private final PurgePlugin plugin;
    private final StateManager stateManager;
    private final GameManager gameManager;
    private final TeamManager teamManager;
    private final LegendaryWeaponRegistry legendaryRegistry;

    private final Map<NamespacedKey, ItemStack> legendaryItems;
    private final Map<NamespacedKey, String> legendaryIdsByRecipeKey;
    private final Map<UUID, Long> legendaryLockMessageMillis;
    private final Set<NamespacedKey> legendaryRecipeKeys;
    private final Set<Integer> spawnedStructureDays;

    private int legendaryCount;
    private boolean legendaryRecipesRegistered;

    public ContentManager(PurgePlugin plugin, StateManager stateManager, GameManager gameManager, TeamManager teamManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.gameManager = gameManager;
        this.teamManager = teamManager;
        this.legendaryRegistry = new LegendaryWeaponRegistry();

        this.legendaryItems = new HashMap<>();
        this.legendaryIdsByRecipeKey = new HashMap<>();
        this.legendaryLockMessageMillis = new HashMap<>();
        this.legendaryRecipeKeys = new HashSet<>();
        this.spawnedStructureDays = new HashSet<>(stateManager.getSpawnedStructureDays());

        this.legendaryCount = Math.max(stateManager.getLegendaryCount(), stateManager.getCraftedLegendaryIds().size());
        this.legendaryRecipesRegistered = false;

        buildLegendaryItems();
    }

    public void onDayChanged(int day) {
        if (day < 3) {
            clearLegendaryProgressIfLockedByDay();
        }

        if (day >= 3 && !legendaryRecipesRegistered) {
            registerLegendaryRecipes();
        }

        if (day <= 5) {
            spawnStructuresForDay(day);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareLegendaryCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shapedRecipe)) {
            return;
        }

        NamespacedKey key = shapedRecipe.getKey();
        if (!legendaryRecipeKeys.contains(key)) {
            return;
        }

        String weaponId = legendaryIdsByRecipeKey.get(key);
        if (weaponId == null) {
            return;
        }

        if (gameManager.getCurrentDay() < 3) {
            notifyLegendaryLockedUntilDayThree(event.getViewers());
            event.getInventory().setResult(new ItemStack(Material.AIR));
            return;
        }

        if (stateManager.isLegendaryCrafted(weaponId)
                || stateManager.getCraftedLegendaryIds().size() >= legendaryRegistry.ids().size()) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftLegendary(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shapedRecipe)) {
            return;
        }

        NamespacedKey key = shapedRecipe.getKey();
        if (!legendaryRecipeKeys.contains(key)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player crafter)) {
            event.setCancelled(true);
            return;
        }

        String weaponId = legendaryIdsByRecipeKey.get(key);
        if (weaponId == null) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.getCurrentDay() < 3) {
            event.setCancelled(true);
            crafter.sendMessage("Legendary crafting unlocks on Day 3.");
            return;
        }

        if (stateManager.isLegendaryCrafted(weaponId)) {
            event.setCancelled(true);
            crafter.sendMessage(getDisplayName(weaponId) + " already exists and cannot be crafted again.");
            return;
        }

        if (stateManager.getCraftedLegendaryIds().size() >= legendaryRegistry.ids().size()) {
            event.setCancelled(true);
            crafter.sendMessage("All legendary weapons already exist for this event.");
            return;
        }

        stateManager.markLegendaryCrafted(weaponId);
        legendaryCount = stateManager.getCraftedLegendaryIds().size();
        stateManager.setLegendaryCount(legendaryCount);
        stateManager.saveStateAsync();

        String teamName = teamManager.getTeamName(crafter.getUniqueId()).orElse("No Team");
        Bukkit.broadcast(Component.text()
            .append(Component.text(crafter.getName(), NamedTextColor.AQUA))
            .append(Component.text(" crafted ", NamedTextColor.GRAY))
            .append(Component.text(getDisplayName(weaponId), NamedTextColor.GOLD))
            .append(Component.text(" [" + legendaryCount + "/4]", NamedTextColor.YELLOW))
            .append(Component.text(" for ", NamedTextColor.GRAY))
            .append(Component.text(teamName, NamedTextColor.GREEN))
            .append(Component.text(".", NamedTextColor.GRAY))
            .build());
    }

    public List<String> getLegendaryWeaponIds() {
        return legendaryRegistry.ids().stream().sorted().toList();
    }

    public boolean isLegendaryCrafted(String weaponId) {
        return stateManager.isLegendaryCrafted(normalizeId(weaponId));
    }

    public boolean giveLegendary(String weaponId, Player target, String sourceName) {
        String normalized = normalizeId(weaponId);
        LegendaryWeapon weapon = legendaryRegistry.byId(normalized).orElse(null);
        if (weapon == null) {
            return false;
        }

        if (stateManager.isLegendaryCrafted(normalized)) {
            return false;
        }

        ItemStack item = weapon.createItem(plugin);
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        for (ItemStack dropped : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), dropped);
        }

        stateManager.markLegendaryCrafted(normalized);
        legendaryCount = stateManager.getCraftedLegendaryIds().size();
        stateManager.setLegendaryCount(legendaryCount);
        stateManager.saveStateAsync();

        Bukkit.broadcast(Component.text()
            .append(Component.text(sourceName, NamedTextColor.AQUA))
            .append(Component.text(" granted ", NamedTextColor.GRAY))
            .append(Component.text(weapon.getDisplayName(), NamedTextColor.GOLD))
            .append(Component.text(" to ", NamedTextColor.GRAY))
            .append(Component.text(target.getName(), NamedTextColor.AQUA))
            .append(Component.text(" [" + legendaryCount + "/4].", NamedTextColor.YELLOW))
            .build());
        return true;
    }

    private void buildLegendaryItems() {
        legendaryItems.clear();
        legendaryIdsByRecipeKey.clear();
        legendaryRecipeKeys.clear();

        for (LegendaryWeapon weapon : legendaryRegistry.all()) {
            ShapedRecipe recipe = weapon.createRecipe(plugin);
            legendaryItems.put(recipe.getKey(), weapon.createItem(plugin));
            legendaryIdsByRecipeKey.put(recipe.getKey(), weapon.getId());
            legendaryRecipeKeys.add(recipe.getKey());
        }
    }

    private void registerLegendaryRecipes() {
        if (legendaryRecipesRegistered) {
            return;
        }

        for (LegendaryWeapon weapon : legendaryRegistry.all()) {
            Bukkit.addRecipe(weapon.createRecipe(plugin));
        }

        legendaryRecipesRegistered = true;
        Bukkit.broadcast(Component.text("Legendary recipes are now unlocked (one of each weapon per event).",
            NamedTextColor.LIGHT_PURPLE));
    }

    private String getDisplayName(String weaponId) {
        return legendaryRegistry.byId(weaponId)
                .map(LegendaryWeapon::getDisplayName)
                .orElse(weaponId);
    }

    private void notifyLegendaryLockedUntilDayThree(List<HumanEntity> viewers) {
        long now = System.currentTimeMillis();
        for (HumanEntity viewer : viewers) {
            if (!(viewer instanceof Player player)) {
                continue;
            }

            UUID playerId = player.getUniqueId();
            long lastMessageAt = legendaryLockMessageMillis.getOrDefault(playerId, 0L);
            if (now - lastMessageAt < LEGENDARY_LOCK_MESSAGE_COOLDOWN_MS) {
                continue;
            }

            legendaryLockMessageMillis.put(playerId, now);
            player.sendMessage(Component.text("Legendary crafting is locked until Day 3.", NamedTextColor.RED));
        }
    }

    private void clearLegendaryProgressIfLockedByDay() {
        if (stateManager.getCraftedLegendaryIds().isEmpty() && stateManager.getLegendaryCount() == 0) {
            legendaryCount = 0;
            return;
        }

        stateManager.clearLegendaryProgress();
        stateManager.saveStateAsync();
        legendaryCount = 0;
    }

    private String normalizeId(String weaponId) {
        if (weaponId == null) {
            return "";
        }
        return weaponId.toLowerCase(Locale.ROOT);
    }

    private void spawnStructuresForDay(int day) {
        if (spawnedStructureDays.contains(day)) {
            return;
        }

        List<String> rawLocations = plugin.getConfig().getStringList("structures.day" + day + ".locations");
        if (rawLocations.isEmpty()) {
            return;
        }

        for (String raw : rawLocations) {
            Location location = parseLocation(raw);
            if (location == null || location.getWorld() == null) {
                continue;
            }

            placeLootChest(location, day);
        }

        spawnedStructureDays.add(day);
        stateManager.setSpawnedStructureDays(spawnedStructureDays);
        stateManager.saveStateAsync();
        Bukkit.broadcast(Component.text("Day " + day + " structures are now active."));
    }

    private void placeLootChest(Location location, int day) {
        Block block = location.getBlock();
        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }

        List<ItemStack> lootPool = getLootPool(day);
        int rolls = Math.min(6, lootPool.size());
        for (int i = 0; i < rolls; i++) {
            ItemStack item = lootPool.get(ThreadLocalRandom.current().nextInt(lootPool.size())).clone();
            chest.getBlockInventory().addItem(item);
        }
    }

    private List<ItemStack> getLootPool(int day) {
        List<ItemStack> loot = new ArrayList<>();

        loot.add(new ItemStack(Material.GOLDEN_APPLE, day >= 2 ? 3 : 1));
        loot.add(new ItemStack(Material.ENDER_PEARL, day >= 2 ? 4 : 2));
        loot.add(new ItemStack(Material.IRON_INGOT, day >= 2 ? 24 : 12));

        if (day >= 2) {
            loot.add(new ItemStack(Material.DIAMOND, 8));
            loot.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 12));
        }
        if (day >= 3) {
            loot.add(new ItemStack(Material.NETHERITE_SCRAP, 4));
            loot.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
        }
        if (day >= 4) {
            loot.add(new ItemStack(Material.BLAZE_ROD, 10));
        }
        if (day >= 5) {
            loot.add(new ItemStack(Material.SHULKER_SHELL, 4));
            loot.add(new ItemStack(Material.ELYTRA, 1));
        }

        return loot;
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
}
