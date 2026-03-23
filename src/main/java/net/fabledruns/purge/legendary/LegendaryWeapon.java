package net.fabledruns.purge.legendary;

import java.util.UUID;
import net.fabledruns.purge.PurgePlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

public interface LegendaryWeapon {

    String getId();

    String getDisplayName();

    UUID getTextureUuid();

    ItemStack createItem(PurgePlugin plugin);

    ShapedRecipe createRecipe(PurgePlugin plugin);
}
