package net.fabledruns.purge.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.system.StateManager;
import net.fabledruns.purge.team.TeamManager;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.recipe.CraftingBookCategory;

public final class ContentManager implements Listener {

    public static final String LEGENDARY_WEAPON_ID_KEY = "legendary_weapon_id";
    public static final String WITHER_BONE_BLADE_ID = "wither_bone_blade";
    public static final String DRAGON_WINGS_ID = "dragon_wings";
    public static final String STONE_FALL_ID = "stone_fall";
    public static final String VOID_SNARE_ID = "void_snare";

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

        legendaryItems.put(
            new NamespacedKey(plugin, "legendary_wither_bone_blade"),
            createLegendaryItem(
                Material.NETHERITE_SWORD,
                "Wither Bone Blade",
                WITHER_BONE_BLADE_ID,
                List.of(
                    "Passive: Strength I while held.",
                    "Active: 10s empowered strikes with wither skull bursts.",
                    "Cooldown: 90s"
                ),
                Map.of(
                    Enchantment.SHARPNESS, 5,
                    Enchantment.UNBREAKING, 3,
                    Enchantment.MENDING, 1
                )
            )
        );

        legendaryItems.put(
            new NamespacedKey(plugin, "legendary_dragon_wings"),
            createLegendaryItem(
                Material.ELYTRA,
                "Dragon Wings",
                DRAGON_WINGS_ID,
                List.of(
                    "Passive: Diamond chestplate-level armor while worn.",
                    "Passive: Mid-air glide toggle (jump, sneak fallback).",
                    "Active: Firework-like boost while gliding.",
                    "Cooldown: 10s"
                ),
                Map.of(
                    Enchantment.UNBREAKING, 3,
                    Enchantment.MENDING, 1
                )
            )
        );

        legendaryItems.put(
            new NamespacedKey(plugin, "legendary_stone_fall"),
            createLegendaryItem(
                Material.MACE,
                "Stone Fall",
                STONE_FALL_ID,
                List.of(
                    "Passive: Negates 75% of fall damage while held.",
                    "Active: Riptide-like forward lunge with hunger drain.",
                    "Cooldown: 1s"
                ),
                Map.of(
                    Enchantment.DENSITY, 5,
                    Enchantment.UNBREAKING, 3,
                    Enchantment.MENDING, 1
                )
            )
        );

        legendaryItems.put(
            new NamespacedKey(plugin, "legendary_void_snare"),
            createLegendaryItem(
                Material.BOW,
                "Void Snare",
                VOID_SNARE_ID,
                List.of(
                    "Passive: Arrows subtly track nearby targets.",
                    "Active: Fully charged void arrow roots and weakens.",
                    "Cooldown: 20s"
                ),
                Map.of(
                    Enchantment.POWER, 5,
                    Enchantment.PUNCH, 2,
                    Enchantment.UNBREAKING, 3,
                    Enchantment.MENDING, 1
                )
            )
        );

        legendaryKeys.addAll(legendaryItems.keySet());
    }

    private ItemStack createLegendaryItem(
            Material material,
            String displayName,
            String weaponId,
            List<String> loreLines,
            Map<Enchantment, Integer> enchantments
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(displayName));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Only four legends exist each event."));
            for (String line : loreLines) {
                lore.add(Component.text(line));
            }
            meta.lore(lore);
            for (Map.Entry<Enchantment, Integer> enchantment : enchantments.entrySet()) {
                meta.addEnchant(enchantment.getKey(), enchantment.getValue(), true);
            }
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, LEGENDARY_WEAPON_ID_KEY),
                    PersistentDataType.STRING,
                    weaponId
            );
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void registerLegendaryRecipes() {
        if (legendaryRecipesRegistered) {
            return;
        }

        registerWitherBoneBladeRecipe();
        registerDragonWingsRecipe();
        registerStoneFallRecipe();
        registerVoidSnareRecipe();

        legendaryRecipesRegistered = true;
        Bukkit.broadcast(Component.text("Legendary recipes are now unlocked (4 total crafts globally)."));
    }

    private void registerWitherBoneBladeRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "legendary_wither_bone_blade");
        ItemStack result = legendaryItems.get(key);
        if (result == null) {
            return;
        }

        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape(" W ", "WSW", " N ");
        recipe.setIngredient('W', Material.WITHER_SKELETON_SKULL);
        recipe.setIngredient('S', Material.NETHERITE_SWORD);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(recipe);
    }

    private void registerDragonWingsRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "legendary_dragon_wings");
        ItemStack result = legendaryItems.get(key);
        if (result == null) {
            return;
        }

        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape("PFP", "FEF", "PFP");
        recipe.setIngredient('P', Material.PHANTOM_MEMBRANE);
        recipe.setIngredient('F', Material.FIREWORK_ROCKET);
        recipe.setIngredient('E', Material.ELYTRA);
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(recipe);
    }

    private void registerStoneFallRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "legendary_stone_fall");
        ItemStack result = legendaryItems.get(key);
        if (result == null) {
            return;
        }

        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape("OOO", "OMO", "NWN");
        recipe.setIngredient('O', Material.OBSIDIAN);
        recipe.setIngredient('M', Material.MACE);
        recipe.setIngredient('N', Material.NETHERITE_INGOT);
        recipe.setIngredient('W', Material.WIND_CHARGE);
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(recipe);
    }

    private void registerVoidSnareRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "legendary_void_snare");
        ItemStack result = legendaryItems.get(key);
        if (result == null) {
            return;
        }

        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape("ESE", "SBS", "ESE");
        recipe.setIngredient('E', Material.ENDER_EYE);
        recipe.setIngredient('S', Material.SHULKER_SHELL);
        recipe.setIngredient('B', Material.BOW);
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(recipe);
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
