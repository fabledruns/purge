package net.fabledruns.purge.legendary.weapons;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabledruns.purge.legendary.BaseLegendaryWeapon;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

public final class VoidSnareWeapon extends BaseLegendaryWeapon {

    public static final String ID = "void_snare";

    public VoidSnareWeapon() {
        super(
                ID,
                "Void Snare",
                Material.BOW,
                UUID.fromString("f5a3d9d4-2f0c-4c34-b3f7-2d3f95e8731b"),
                NamedTextColor.DARK_AQUA,
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
                ),
                new String[]{"ESE", "SBS", "ESE"},
                Map.of(
                        'E', Material.ENDER_EYE,
                        'S', Material.SHULKER_SHELL,
                        'B', Material.BOW
                )
        );
    }
}
