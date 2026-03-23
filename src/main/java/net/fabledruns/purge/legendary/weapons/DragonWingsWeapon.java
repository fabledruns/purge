package net.fabledruns.purge.legendary.weapons;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabledruns.purge.legendary.BaseLegendaryWeapon;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

public final class DragonWingsWeapon extends BaseLegendaryWeapon {

    public static final String ID = "dragon_wings";

    public DragonWingsWeapon() {
        super(
                ID,
                "Dragon Wings",
                Material.ELYTRA,
                UUID.fromString("8c4e7bb7-cdd7-4a8f-85fd-9c0494f51277"),
                NamedTextColor.LIGHT_PURPLE,
                List.of(
                        "Passive: Diamond chestplate-level armor while worn.",
                        "Passive: Mid-air glide toggle (jump, sneak fallback).",
                        "Passive: High-speed elytra landings explode on impact.",
                        "Passive: Impact damage is negated for the wearer."
                ),
                Map.of(
                        Enchantment.UNBREAKING, 3,
                        Enchantment.MENDING, 1
                ),
                new String[]{"PFP", "FEF", "PFP"},
                Map.of(
                        'P', Material.PHANTOM_MEMBRANE,
                        'F', Material.FIREWORK_ROCKET,
                        'E', Material.ELYTRA
                )
        );
    }
}
