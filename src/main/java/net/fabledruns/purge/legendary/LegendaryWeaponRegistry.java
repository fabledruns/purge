package net.fabledruns.purge.legendary;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabledruns.purge.legendary.weapons.DragonWingsWeapon;
import net.fabledruns.purge.legendary.weapons.StoneFallWeapon;
import net.fabledruns.purge.legendary.weapons.VoidSnareWeapon;
import net.fabledruns.purge.legendary.weapons.WitherBoneBladeWeapon;

public final class LegendaryWeaponRegistry {

    private final Map<String, LegendaryWeapon> weaponsById;

    public LegendaryWeaponRegistry() {
        this.weaponsById = new LinkedHashMap<>();

        register(new WitherBoneBladeWeapon());
        register(new DragonWingsWeapon());
        register(new StoneFallWeapon());
        register(new VoidSnareWeapon());
    }

    public Collection<LegendaryWeapon> all() {
        return Collections.unmodifiableCollection(weaponsById.values());
    }

    public Optional<LegendaryWeapon> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(weaponsById.get(id.toLowerCase(Locale.ROOT)));
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(weaponsById.keySet());
    }

    private void register(LegendaryWeapon weapon) {
        weaponsById.put(weapon.getId().toLowerCase(Locale.ROOT), weapon);
    }
}
