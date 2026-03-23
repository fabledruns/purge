package net.fabledruns.purge.legendary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabledruns.purge.PurgePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataType;

public abstract class BaseLegendaryWeapon implements LegendaryWeapon {

    private final String id;
    private final String displayName;
    private final Material material;
    private final UUID textureUuid;
    private final NamedTextColor nameColor;
    private final List<String> loreLines;
    private final Map<Enchantment, Integer> enchantments;
    private final String[] shape;
    private final Map<Character, Material> ingredients;

    protected BaseLegendaryWeapon(
            String id,
            String displayName,
            Material material,
            UUID textureUuid,
            NamedTextColor nameColor,
            List<String> loreLines,
            Map<Enchantment, Integer> enchantments,
            String[] shape,
            Map<Character, Material> ingredients
    ) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.textureUuid = textureUuid;
        this.nameColor = nameColor;
        this.loreLines = List.copyOf(loreLines);
        this.enchantments = Map.copyOf(enchantments);
        this.shape = shape.clone();
        this.ingredients = Map.copyOf(ingredients);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public UUID getTextureUuid() {
        return textureUuid;
    }

    @Override
    public ItemStack createItem(PurgePlugin plugin) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(Component.text(displayName, nameColor, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Legendary Weapon", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (String line : loreLines) {
            lore.add(styleLoreLine(line));
        }
        lore.add(Component.empty());
        lore.add(Component.text("LEGENDARY", NamedTextColor.GOLD, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        for (Map.Entry<Enchantment, Integer> enchantment : enchantments.entrySet()) {
            meta.addEnchant(enchantment.getKey(), enchantment.getValue(), true);
        }

        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, LegendaryKeys.WEAPON_ID_KEY),
                PersistentDataType.STRING,
                id
        );

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public ShapedRecipe createRecipe(PurgePlugin plugin) {
        NamespacedKey key = new NamespacedKey(plugin, "legendary_" + id);
        ShapedRecipe recipe = new ShapedRecipe(key, createItem(plugin));
        recipe.shape(shape);
        for (Map.Entry<Character, Material> ingredient : ingredients.entrySet()) {
            recipe.setIngredient(ingredient.getKey(), ingredient.getValue());
        }
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        return recipe;
    }

    private Component styleLoreLine(String line) {
        if (line.startsWith("Passive:")) {
            return Component.text("Passive: ", NamedTextColor.GREEN)
                    .append(Component.text(line.substring("Passive:".length()).trim(), NamedTextColor.GRAY))
                    .decoration(TextDecoration.ITALIC, false);
        }

        if (line.startsWith("Active:")) {
            return Component.text("Ability: ", NamedTextColor.AQUA)
                    .append(Component.text(line.substring("Active:".length()).trim(), NamedTextColor.GRAY))
                    .decoration(TextDecoration.ITALIC, false);
        }

        if (line.startsWith("Cooldown:")) {
            return Component.text("Cooldown: ", NamedTextColor.YELLOW)
                    .append(Component.text(line.substring("Cooldown:".length()).trim(), NamedTextColor.GRAY))
                    .decoration(TextDecoration.ITALIC, false);
        }

        return Component.text(line, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
