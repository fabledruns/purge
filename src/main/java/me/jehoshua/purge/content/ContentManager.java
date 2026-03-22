package me.jehoshua.purge.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import me.jehoshua.purge.PurgePlugin;
import me.jehoshua.purge.game.GameManager;
import me.jehoshua.purge.state.StateManager;
import me.jehoshua.purge.team.TeamManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;

public final class ContentManager implements Listener {

    private final PurgePlugin plugin;
    private final StateManager stateManager;
    private final GameManager gameManager;
    private final TeamManager teamManager;

    private final Map<NamespacedKey, ItemStack> legendaryItems;
    private final Set<NamespacedKey> legendaryKeys;
    private final Set<Integer> spawnedStructureDays;

    private int legendaryCount;
    private boolean legendaryRecipesRegistered;

    public ContentManager(PurgePlugin plugin, StateManager stateManager, GameManager gameManager, TeamManager teamManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.gameManager = gameManager;
        this.teamManager = teamManager;

        this.legendaryItems = new HashMap<>();
        this.legendaryKeys = new HashSet<>();
        this.spawnedStructureDays = new HashSet<>();

        this.legendaryCount = stateManager.getLegendaryCount();
        this.legendaryRecipesRegistered = false;

        buildLegendaryItems();
    }

    public void onDayChanged(int day) {
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
        if (!legendaryKeys.contains(key)) {
            return;
        }

        if (gameManager.getCurrentDay() < 3 || legendaryCount >= 4) {
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
        if (!legendaryKeys.contains(key)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player crafter)) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.getCurrentDay() < 3) {
            event.setCancelled(true);
            crafter.sendMessage("Legendary crafting unlocks on Day 3.");
            return;
        }

        if (legendaryCount >= 4) {
            event.setCancelled(true);
            crafter.sendMessage("Global legendary cap reached (4/4).");
            return;
        }

        legendaryCount++;
        stateManager.setLegendaryCount(legendaryCount);
        stateManager.saveStateAsync();

        String teamName = teamManager.getTeamName(crafter.getUniqueId()).orElse("No Team");
        Bukkit.broadcast(Component.text(crafter.getName()
                + " crafted a legendary weapon [" + legendaryCount + "/4] for " + teamName + "."));
    }

    private void buildLegendaryItems() {
        legendaryItems.clear();
        legendaryKeys.clear();

        legendaryItems.put(new NamespacedKey(plugin, "legendary_blade"), createLegendaryItem(Material.NETHERITE_SWORD, "Legendary Blade"));
        legendaryItems.put(new NamespacedKey(plugin, "legendary_axe"), createLegendaryItem(Material.NETHERITE_AXE, "Legendary Axe"));
        legendaryItems.put(new NamespacedKey(plugin, "legendary_trident"), createLegendaryItem(Material.TRIDENT, "Legendary Trident"));
        legendaryItems.put(new NamespacedKey(plugin, "legendary_bow"), createLegendaryItem(Material.BOW, "Legendary Bow"));

        legendaryKeys.addAll(legendaryItems.keySet());
    }

    private ItemStack createLegendaryItem(Material material, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(displayName));
            meta.lore(List.of(Component.text("Only four legends exist each event.")));
            meta.addEnchant(Enchantment.UNBREAKING, 3, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void registerLegendaryRecipes() {
        if (legendaryRecipesRegistered) {
            return;
        }

        for (Map.Entry<NamespacedKey, ItemStack> entry : legendaryItems.entrySet()) {
            NamespacedKey key = entry.getKey();
            ItemStack result = entry.getValue().clone();

            ShapedRecipe recipe = new ShapedRecipe(key, result);
            recipe.shape("NDN", "DBD", "NDN");
            recipe.setIngredient('N', Material.NETHERITE_INGOT);
            recipe.setIngredient('D', Material.DIAMOND_BLOCK);
            recipe.setIngredient('B', Material.BLAZE_ROD);
            recipe.setCategory(CraftingBookCategory.EQUIPMENT);

            Bukkit.addRecipe(recipe);
        }

        legendaryRecipesRegistered = true;
        Bukkit.broadcast(Component.text("Legendary recipes are now unlocked (4 total crafts globally)."));
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
