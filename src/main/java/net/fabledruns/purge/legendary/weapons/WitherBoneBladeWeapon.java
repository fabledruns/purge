package net.fabledruns.purge.legendary.weapons;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabledruns.purge.legendary.BaseLegendaryWeapon;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

public final class WitherBoneBladeWeapon extends BaseLegendaryWeapon {

    public static final String ID = "wither_bone_blade";

    public WitherBoneBladeWeapon() {
        super(
                ID,
                "Wither Bone Blade",
                Material.NETHERITE_SWORD,
                UUID.fromString("3ad5a8f2-0a2c-4d7e-9aaf-5f3bf4d50811"),
                NamedTextColor.RED,
                List.of(
                        "Passive: Strength I while held.",
                        "Active: 10s empowered strikes with wither skull bursts.",
                        "Cooldown: 90s"
                ),
                Map.of(
                        Enchantment.SHARPNESS, 5,
                        Enchantment.UNBREAKING, 3,
                        Enchantment.MENDING, 1
                ),
                new String[]{" W ", "WSW", " N "},
                Map.of(
                        'W', Material.WITHER_SKELETON_SKULL,
                        'S', Material.NETHERITE_SWORD,
                        'N', Material.NETHER_STAR
                )
        );
    }
}
