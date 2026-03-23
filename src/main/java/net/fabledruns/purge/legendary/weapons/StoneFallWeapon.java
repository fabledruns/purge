package net.fabledruns.purge.legendary.weapons;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabledruns.purge.legendary.BaseLegendaryWeapon;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

public final class StoneFallWeapon extends BaseLegendaryWeapon {

    public static final String ID = "stone_fall";

    public StoneFallWeapon() {
        super(
                ID,
                "Stone Fall",
                Material.MACE,
                UUID.fromString("0df9f55f-7e9f-4e4f-8f2b-b8ad8dd6f6f3"),
                NamedTextColor.GOLD,
                List.of(
                        "Passive: Negates 75% of fall damage while held.",
                        "Active: Riptide-like forward lunge with hunger drain.",
                        "Cooldown: 1s"
                ),
                Map.of(
                        Enchantment.DENSITY, 5,
                        Enchantment.UNBREAKING, 3,
                        Enchantment.MENDING, 1
                ),
                new String[]{"OOO", "OMO", "NWN"},
                Map.of(
                        'O', Material.OBSIDIAN,
                        'M', Material.MACE,
                        'N', Material.NETHERITE_INGOT,
                        'W', Material.WIND_CHARGE
                )
        );
    }
}
