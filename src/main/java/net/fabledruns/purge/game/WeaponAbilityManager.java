package net.fabledruns.purge.game;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.fabledruns.purge.PurgePlugin;
import net.fabledruns.purge.system.PlayerManager;
import net.fabledruns.purge.system.WorldManager;
import net.fabledruns.purge.team.TeamManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class WeaponAbilityManager implements Listener {

    private final PurgePlugin plugin;
    private final PlayerManager playerManager;
    private final GameManager gameManager;
    private final WorldManager worldManager;
    private final TeamManager teamManager;

    private final NamespacedKey weaponIdKey;
    private final NamespacedKey voidHomingArrowKey;
    private final NamespacedKey voidSpecialArrowKey;
    private final NamespacedKey dragonArmorModifierKey;
    private final NamespacedKey dragonToughnessModifierKey;

    private final Map<UUID, Long> witherCooldownUntil;
    private final Map<UUID, Long> witherEmpoweredUntil;
    private final Map<UUID, SkullRateWindow> skullRateWindows;

    private final Map<UUID, Long> dragonCooldownUntil;
    private final Map<UUID, Long> dragonToggleDebounceUntil;

    private final Map<UUID, Long> stoneCooldownUntil;

    private final Map<UUID, Long> voidSnareCooldownUntil;
    private final Map<UUID, UUID> homingArrows;
    private final Map<UUID, RootState> rootedPlayers;

    private BukkitTask passiveSyncTask;
    private BukkitTask homingTask;
    private BukkitTask rootTask;
    private BukkitTask cleanupTask;

    public WeaponAbilityManager(
            PurgePlugin plugin,
            PlayerManager playerManager,
            GameManager gameManager,
            WorldManager worldManager,
            TeamManager teamManager
    ) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.gameManager = gameManager;
        this.worldManager = worldManager;
        this.teamManager = teamManager;

        this.weaponIdKey = new NamespacedKey(plugin, ContentManager.LEGENDARY_WEAPON_ID_KEY);
        this.voidHomingArrowKey = new NamespacedKey(plugin, "void_snare_homing_arrow");
        this.voidSpecialArrowKey = new NamespacedKey(plugin, "void_snare_special_arrow");
        this.dragonArmorModifierKey = new NamespacedKey(plugin, "dragon_wings_armor_modifier");
        this.dragonToughnessModifierKey = new NamespacedKey(plugin, "dragon_wings_toughness_modifier");

        this.witherCooldownUntil = new ConcurrentHashMap<>();
        this.witherEmpoweredUntil = new ConcurrentHashMap<>();
        this.skullRateWindows = new ConcurrentHashMap<>();

        this.dragonCooldownUntil = new ConcurrentHashMap<>();
        this.dragonToggleDebounceUntil = new ConcurrentHashMap<>();

        this.stoneCooldownUntil = new ConcurrentHashMap<>();

        this.voidSnareCooldownUntil = new ConcurrentHashMap<>();
        this.homingArrows = new ConcurrentHashMap<>();
        this.rootedPlayers = new ConcurrentHashMap<>();
    }

    public void start() {
        if (passiveSyncTask != null) {
            return;
        }

        passiveSyncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncPassiveStates, 1L, 20L);
        homingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processHomingArrows, 2L, getVoidHomingIntervalTicks());
        rootTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processRootExpirations, 1L, 1L);
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupState, 40L, 40L);
    }

    public void stop() {
        cancelTask(passiveSyncTask);
        passiveSyncTask = null;

        cancelTask(homingTask);
        homingTask = null;

        cancelTask(rootTask);
        rootTask = null;

        cancelTask(cleanupTask);
        cleanupTask = null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            removeWitherStrength(player);
            syncDragonArmorModifiers(player, false);
            clearRoot(player.getUniqueId());
        }

        witherCooldownUntil.clear();
        witherEmpoweredUntil.clear();
        skullRateWindows.clear();
        dragonCooldownUntil.clear();
        dragonToggleDebounceUntil.clear();
        stoneCooldownUntil.clear();
        voidSnareCooldownUntil.clear();
        homingArrows.clear();
        rootedPlayers.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            syncWitherStrengthPassive(player);
            syncDragonArmorModifiers(player, hasWeapon(player.getInventory().getChestplate(), ContentManager.DRAGON_WINGS_ID));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        removeWitherStrength(player);
        syncDragonArmorModifiers(player, false);
        clearRoot(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        removeWitherStrength(player);
        syncDragonArmorModifiers(player, false);
        clearRoot(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncWitherStrengthPassive(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncWitherStrengthPassive(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncWitherStrengthPassive(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            syncWitherStrengthPassive(player);
            syncDragonArmorModifiers(player, hasWeapon(player.getInventory().getChestplate(), ContentManager.DRAGON_WINGS_ID));
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRightClickAbility(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
            }
            default -> {
                return;
            }
        }

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
            && event.useInteractedBlock() != Event.Result.DENY) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        String weaponId = getWeaponId(mainHand);

        if (ContentManager.WITHER_BONE_BLADE_ID.equals(weaponId)) {
            activateWitherBoneBlade(player);
            return;
        }

        if (ContentManager.STONE_FALL_ID.equals(weaponId)) {
            activateStoneFall(player);
            return;
        }

        if (hasWeapon(player.getInventory().getChestplate(), ContentManager.DRAGON_WINGS_ID)) {
            activateDragonWingsBoost(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWitherBladeDamageBoost(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        long now = System.currentTimeMillis();
        long empoweredUntil = witherEmpoweredUntil.getOrDefault(attacker.getUniqueId(), 0L);
        if (empoweredUntil <= now) {
            return;
        }

        if (!hasWeapon(attacker.getInventory().getItemInMainHand(), ContentManager.WITHER_BONE_BLADE_ID)) {
            return;
        }

        double multiplier = Math.max(1.0D, plugin.getConfig().getDouble("legendary-weapons.wither-bone-blade.damage-multiplier", 1.25D));
        event.setDamage(event.getDamage() * multiplier);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWitherBladeSkullProc(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        long now = System.currentTimeMillis();
        long empoweredUntil = witherEmpoweredUntil.getOrDefault(attacker.getUniqueId(), 0L);
        if (empoweredUntil <= now) {
            return;
        }

        if (!hasWeapon(attacker.getInventory().getItemInMainHand(), ContentManager.WITHER_BONE_BLADE_ID)) {
            return;
        }

        if (victim instanceof Player targetPlayer
                && !teamManager.canFriendlyFire(attacker.getUniqueId(), targetPlayer.getUniqueId(), gameManager.getCurrentDay())) {
            return;
        }

        double chance = clamp(
                plugin.getConfig().getDouble("legendary-weapons.wither-bone-blade.skull-chance", 0.5D),
                0.0D,
                1.0D
        );
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        int skullsPerProc = Math.max(1, plugin.getConfig().getInt("legendary-weapons.wither-bone-blade.skulls-per-proc", 3));
        int maxPerSecond = Math.max(skullsPerProc, plugin.getConfig().getInt("legendary-weapons.wither-bone-blade.max-skulls-per-second", 12));

        SkullRateWindow window = skullRateWindows.computeIfAbsent(attacker.getUniqueId(), ignored -> new SkullRateWindow(now, 0));
        if (now - window.windowStartMillis > 1000L) {
            window.windowStartMillis = now;
            window.spawnedCount = 0;
        }

        if (window.spawnedCount + skullsPerProc > maxPerSecond) {
            return;
        }

        spawnWitherSkulls(attacker, victim, skullsPerProc);
        window.spawnedCount += skullsPerProc;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStoneFallPassive(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        if (!hasWeapon(player.getInventory().getItemInMainHand(), ContentManager.STONE_FALL_ID)) {
            return;
        }

        double reduction = clamp(
                plugin.getConfig().getDouble("legendary-weapons.stone-fall.fall-damage-reduction", 0.75D),
                0.0D,
                1.0D
        );
        event.setDamage(event.getDamage() * (1.0D - reduction));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVoidSnareBowShot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) {
            return;
        }

        if (!hasWeapon(event.getBow(), ContentManager.VOID_SNARE_ID)) {
            return;
        }

        if (!(event.getProjectile() instanceof AbstractArrow arrow)) {
            return;
        }

        if (!playerManager.isAlive(shooter.getUniqueId())) {
            return;
        }

        PersistentDataContainer data = arrow.getPersistentDataContainer();
        data.set(voidHomingArrowKey, PersistentDataType.BYTE, (byte) 1);
        homingArrows.put(arrow.getUniqueId(), shooter.getUniqueId());

        double fullChargeForce = clamp(
                plugin.getConfig().getDouble("legendary-weapons.void-snare.full-charge-force", 0.98D),
                0.0D,
                1.0D
        );
        if (event.getForce() < fullChargeForce) {
            return;
        }

        if (!canUseCombatAbility(shooter, shooter.getLocation())) {
            return;
        }

        UUID shooterId = shooter.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownUntil = voidSnareCooldownUntil.getOrDefault(shooterId, 0L);
        if (cooldownUntil > now) {
            sendCooldownMessage(shooter, "Void Snare", cooldownUntil - now);
            return;
        }

        long cooldownMillis = Math.max(1L, plugin.getConfig().getLong("legendary-weapons.void-snare.cooldown-seconds", 20L)) * 1000L;
        voidSnareCooldownUntil.put(shooterId, now + cooldownMillis);
        data.set(voidSpecialArrowKey, PersistentDataType.BYTE, (byte) 1);
        shooter.sendActionBar(Component.text("Void arrow primed."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVoidSnareArrowHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        PersistentDataContainer data = arrow.getPersistentDataContainer();
        Byte marker = data.get(voidSpecialArrowKey, PersistentDataType.BYTE);
        if (marker == null || marker != (byte) 1) {
            return;
        }

        if (!(arrow.getShooter() instanceof Player shooter)) {
            return;
        }

        if (!playerManager.isAlive(victim.getUniqueId())) {
            return;
        }

        if (!worldManager.isPvpAllowed(victim.getLocation())) {
            return;
        }

        if (!teamManager.canFriendlyFire(shooter.getUniqueId(), victim.getUniqueId(), gameManager.getCurrentDay())) {
            return;
        }

        int rootTicks = Math.max(1, plugin.getConfig().getInt("legendary-weapons.void-snare.root-ticks", 30));
        int weaknessTicks = Math.max(1, plugin.getConfig().getInt("legendary-weapons.void-snare.weakness-ticks", 80));

        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weaknessTicks, 0, false, true, true));
        applyRoot(victim, rootTicks);

        Location hitAt = victim.getLocation().add(0.0D, 1.0D, 0.0D);
        victim.getWorld().spawnParticle(Particle.SQUID_INK, hitAt, 18, 0.4D, 0.4D, 0.4D, 0.02D);
        victim.getWorld().spawnParticle(Particle.SOUL, hitAt, 12, 0.35D, 0.35D, 0.35D, 0.01D);
        victim.playSound(victim.getLocation(), Sound.ENTITY_ENDERMAN_HURT, 0.8F, 0.75F);

        homingArrows.remove(arrow.getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDragonWingsJumpToggle(PlayerJumpEvent event) {
        if (!plugin.getConfig().getBoolean("legendary-weapons.dragon-wings.jump-toggle-enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (!hasWeapon(player.getInventory().getChestplate(), ContentManager.DRAGON_WINGS_ID)) {
            return;
        }

        if (isGrounded(player)) {
            return;
        }

        if (!canToggleGlide(player)) {
            return;
        }

        toggleGliding(player);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDragonWingsSneakFallback(PlayerToggleSneakEvent event) {
        if (!plugin.getConfig().getBoolean("legendary-weapons.dragon-wings.sneak-fallback-enabled", true)) {
            return;
        }

        if (!event.isSneaking()) {
            return;
        }

        Player player = event.getPlayer();
        if (!hasWeapon(player.getInventory().getChestplate(), ContentManager.DRAGON_WINGS_ID)) {
            return;
        }

        if (isGrounded(player)) {
            return;
        }

        if (!canToggleGlide(player)) {
            return;
        }

        toggleGliding(player);
    }

    private void syncPassiveStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncWitherStrengthPassive(player);
            syncDragonArmorModifiers(player, hasWeapon(player.getInventory().getChestplate(), ContentManager.DRAGON_WINGS_ID));
        }
    }

    private void processRootExpirations() {
        if (rootedPlayers.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, RootState>> iterator = rootedPlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RootState> entry = iterator.next();
            UUID playerId = entry.getKey();
            RootState state = entry.getValue();

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead() || now >= state.expireAtMillis) {
                iterator.remove();
                restoreRootedPlayer(player, state);
            }
        }
    }

    private void cleanupState() {
        long now = System.currentTimeMillis();

        removeExpired(witherCooldownUntil, now);
        removeExpired(witherEmpoweredUntil, now);
        removeExpired(dragonCooldownUntil, now);
        removeExpired(dragonToggleDebounceUntil, now);
        removeExpired(stoneCooldownUntil, now);
        removeExpired(voidSnareCooldownUntil, now);

        skullRateWindows.entrySet().removeIf(entry -> now - entry.getValue().windowStartMillis > 2000L);

        homingArrows.entrySet().removeIf(entry -> {
            Entity entity = Bukkit.getEntity(entry.getKey());
            return !(entity instanceof Arrow arrow) || !arrow.isValid() || arrow.isDead();
        });

        rootedPlayers.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            return player == null || !player.isOnline() || player.isDead();
        });
    }

    private void activateWitherBoneBlade(Player player) {
        if (!canUseCombatAbility(player, player.getLocation())) {
            player.sendActionBar(Component.text("Cannot activate here."));
            return;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownUntil = witherCooldownUntil.getOrDefault(playerId, 0L);
        if (cooldownUntil > now) {
            sendCooldownMessage(player, "Wither Bone Blade", cooldownUntil - now);
            return;
        }

        long cooldownMillis = Math.max(1L, plugin.getConfig().getLong("legendary-weapons.wither-bone-blade.cooldown-seconds", 90L)) * 1000L;
        long activeMillis = Math.max(1L, plugin.getConfig().getLong("legendary-weapons.wither-bone-blade.empowered-seconds", 10L)) * 1000L;

        witherCooldownUntil.put(playerId, now + cooldownMillis);
        witherEmpoweredUntil.put(playerId, now + activeMillis);
        player.sendActionBar(Component.text("Wither Bone Blade empowered for 10s."));
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.4F, 1.5F);
    }

    private void activateDragonWingsBoost(Player player) {
        if (!player.isGliding()) {
            return;
        }

        if (!canUseCombatAbility(player, player.getLocation())) {
            player.sendActionBar(Component.text("Cannot activate here."));
            return;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownUntil = dragonCooldownUntil.getOrDefault(playerId, 0L);
        if (cooldownUntil > now) {
            sendCooldownMessage(player, "Dragon Wings", cooldownUntil - now);
            return;
        }

        long cooldownMillis = Math.max(1L, plugin.getConfig().getLong("legendary-weapons.dragon-wings.cooldown-seconds", 10L)) * 1000L;
        dragonCooldownUntil.put(playerId, now + cooldownMillis);

        double multiplier = Math.max(0.1D, plugin.getConfig().getDouble("legendary-weapons.dragon-wings.boost-multiplier", 1.2D));
        Vector boost = player.getLocation().getDirection().normalize().multiply(multiplier).add(new Vector(0.0D, 0.12D, 0.0D));
        player.setVelocity(player.getVelocity().add(boost));
        player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 14, 0.2D, 0.2D, 0.2D, 0.02D);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7F, 1.2F);
    }

    private void activateStoneFall(Player player) {
        if (!canUseCombatAbility(player, player.getLocation())) {
            player.sendActionBar(Component.text("Cannot activate here."));
            return;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownUntil = stoneCooldownUntil.getOrDefault(playerId, 0L);
        if (cooldownUntil > now) {
            sendCooldownMessage(player, "Stone Fall", cooldownUntil - now);
            return;
        }

        long cooldownMillis = Math.max(1L, plugin.getConfig().getLong("legendary-weapons.stone-fall.cooldown-seconds", 1L)) * 1000L;
        stoneCooldownUntil.put(playerId, now + cooldownMillis);

        double lungePower = Math.max(0.1D, plugin.getConfig().getDouble("legendary-weapons.stone-fall.lunge-multiplier", 2.25D));
        Vector launch = player.getLocation().getDirection().normalize().multiply(lungePower);
        if (launch.getY() < 0.2D) {
            launch.setY(0.2D);
        }
        player.setVelocity(launch);

        float saturationDrain = (float) Math.max(0.0D, plugin.getConfig().getDouble("legendary-weapons.stone-fall.saturation-drain", 2.5D));
        int foodDrain = Math.max(0, plugin.getConfig().getInt("legendary-weapons.stone-fall.food-drain", 0));
        float exhaustionAdd = (float) Math.max(0.0D, plugin.getConfig().getDouble("legendary-weapons.stone-fall.exhaustion-add", 1.5D));

        player.setSaturation(Math.max(0.0F, player.getSaturation() - saturationDrain));
        if (foodDrain > 0) {
            player.setFoodLevel(Math.max(0, player.getFoodLevel() - foodDrain));
        }
        player.setExhaustion(Math.min(40.0F, player.getExhaustion() + exhaustionAdd));
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 0.85F, 1.25F);
    }

    private void processHomingArrows() {
        if (homingArrows.isEmpty()) {
            return;
        }

        int maxArrowLifeTicks = Math.max(20, plugin.getConfig().getInt("legendary-weapons.void-snare.max-arrow-life-ticks", 200));
        double range = Math.max(1.0D, plugin.getConfig().getDouble("legendary-weapons.void-snare.homing-range", 6.0D));
        double turnRate = clamp(
                plugin.getConfig().getDouble("legendary-weapons.void-snare.homing-turn-rate", 0.08D),
                0.01D,
                0.25D
        );

        Iterator<Map.Entry<UUID, UUID>> iterator = homingArrows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();

            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Arrow arrow) || !arrow.isValid() || arrow.isDead()) {
                iterator.remove();
                continue;
            }

            if (arrow.getTicksLived() > maxArrowLifeTicks || arrow.isInBlock()) {
                iterator.remove();
                continue;
            }

            Player shooter = Bukkit.getPlayer(entry.getValue());
            if (shooter == null || !shooter.isOnline()) {
                iterator.remove();
                continue;
            }

            Player target = findHomingTarget(arrow, shooter, range);
            if (target == null) {
                continue;
            }

            Vector velocity = arrow.getVelocity();
            double speed = velocity.length();
            if (speed <= 0.01D) {
                continue;
            }

            Vector desiredDirection = target.getEyeLocation().toVector().subtract(arrow.getLocation().toVector()).normalize();
            Vector adjusted = velocity.clone().multiply(1.0D - turnRate).add(desiredDirection.multiply(speed * turnRate));
            arrow.setVelocity(adjusted);
        }
    }

    private Player findHomingTarget(Arrow arrow, Player shooter, double range) {
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : arrow.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Player target) || target.isDead() || target.getUniqueId().equals(shooter.getUniqueId())) {
                continue;
            }

            if (!playerManager.isAlive(target.getUniqueId())) {
                continue;
            }

            if (!teamManager.canFriendlyFire(shooter.getUniqueId(), target.getUniqueId(), gameManager.getCurrentDay())) {
                continue;
            }

            double dist = arrow.getLocation().distanceSquared(target.getLocation());
            if (dist < nearestDistanceSquared) {
                nearestDistanceSquared = dist;
                nearest = target;
            }
        }

        return nearest;
    }

    private void spawnWitherSkulls(Player attacker, LivingEntity victim, int count) {
        Location attackerEye = attacker.getEyeLocation();
        Location targetEye = victim.getEyeLocation();

        for (int i = 0; i < count; i++) {
            Vector direction = targetEye.toVector().subtract(attackerEye.toVector()).normalize();
            direction.add(new Vector(
                    ThreadLocalRandom.current().nextDouble(-0.08D, 0.08D),
                    ThreadLocalRandom.current().nextDouble(-0.05D, 0.05D),
                    ThreadLocalRandom.current().nextDouble(-0.08D, 0.08D)
            )).normalize();

            Location spawnAt = attackerEye.clone().add(direction.clone().multiply(0.35D));
            WitherSkull skull = attacker.getWorld().spawn(spawnAt, WitherSkull.class);
            skull.setShooter(attacker);
            skull.setVelocity(direction.multiply(1.05D));
        }
    }

    private void applyRoot(Player player, int rootTicks) {
        long now = System.currentTimeMillis();
        RootState existing = rootedPlayers.get(player.getUniqueId());
        if (existing != null && existing.expireAtMillis > now) {
            return;
        }

        long expireAt = now + (rootTicks * 50L);
        RootState state = new RootState(expireAt, player.getWalkSpeed(), player.getFlySpeed());
        rootedPlayers.put(player.getUniqueId(), state);

        player.setWalkSpeed(0.0F);
        player.setFlySpeed(0.0F);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, rootTicks + 4, 10, false, false, false));
    }

    private void clearRoot(UUID playerId) {
        RootState state = rootedPlayers.remove(playerId);
        if (state == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        restoreRootedPlayer(player, state);
    }

    private void restoreRootedPlayer(Player player, RootState state) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.setWalkSpeed(state.walkSpeed);
        player.setFlySpeed(state.flySpeed);

        PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null && slow.getAmplifier() >= 10) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    private void syncWitherStrengthPassive(Player player) {
        boolean shouldApply = playerManager.isAlive(player.getUniqueId())
                && hasWeapon(player.getInventory().getItemInMainHand(), ContentManager.WITHER_BONE_BLADE_ID);

        if (shouldApply) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 40, 0, false, false, false));
            return;
        }

        removeWitherStrength(player);
    }

    private void removeWitherStrength(Player player) {
        PotionEffect strength = player.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength == null) {
            return;
        }

        if (strength.getAmplifier() == 0 && !strength.isAmbient() && !strength.hasParticles()) {
            player.removePotionEffect(PotionEffectType.STRENGTH);
        }
    }

    private void syncDragonArmorModifiers(Player player, boolean wearingDragonWings) {
        AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
        AttributeInstance toughness = player.getAttribute(Attribute.ARMOR_TOUGHNESS);

        removeModifierByKey(armor, dragonArmorModifierKey);
        removeModifierByKey(toughness, dragonToughnessModifierKey);

        if (!wearingDragonWings) {
            return;
        }

        if (armor != null) {
            armor.addModifier(new AttributeModifier(
                    dragonArmorModifierKey,
                    8.0D,
                    AttributeModifier.Operation.ADD_NUMBER
            ));
        }

        if (toughness != null) {
            toughness.addModifier(new AttributeModifier(
                    dragonToughnessModifierKey,
                    2.0D,
                    AttributeModifier.Operation.ADD_NUMBER
            ));
        }
    }

    private void removeModifierByKey(AttributeInstance attribute, NamespacedKey modifierKey) {
        if (attribute == null) {
            return;
        }

        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.getKey().equals(modifierKey)) {
                attribute.removeModifier(modifier);
            }
        }
    }

    private boolean isGrounded(Player player) {
        Location feet = player.getLocation().clone().subtract(0.0D, 0.12D, 0.0D);
        return feet.getBlock().getType().isSolid();
    }

    private boolean canToggleGlide(Player player) {
        long now = System.currentTimeMillis();
        long debounceUntil = dragonToggleDebounceUntil.getOrDefault(player.getUniqueId(), 0L);
        if (debounceUntil > now) {
            return false;
        }

        long debounceMs = Math.max(40L, plugin.getConfig().getLong("legendary-weapons.dragon-wings.toggle-debounce-ms", 140L));
        dragonToggleDebounceUntil.put(player.getUniqueId(), now + debounceMs);
        return true;
    }

    private void toggleGliding(Player player) {
        boolean nextState = !player.isGliding();
        player.setGliding(nextState);
        player.sendActionBar(Component.text(nextState ? "Dragon Wings gliding enabled." : "Dragon Wings gliding disabled."));
    }

    private int getVoidHomingIntervalTicks() {
        return Math.max(1, plugin.getConfig().getInt("legendary-weapons.void-snare.homing-check-interval-ticks", 2));
    }

    private void sendCooldownMessage(Player player, String abilityName, long remainingMillis) {
        double seconds = remainingMillis / 1000.0D;
        player.sendActionBar(Component.text(
                abilityName + " cooldown: " + String.format(Locale.ROOT, "%.1f", seconds) + "s"
        ));
    }

    private boolean hasWeapon(ItemStack item, String weaponId) {
        return weaponId.equals(getWeaponId(item));
    }

    private String getWeaponId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return "";
        }

        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        String stored = data.get(weaponIdKey, PersistentDataType.STRING);
        return stored == null ? "" : stored;
    }

    private boolean canUseCombatAbility(Player player, Location at) {
        if (!playerManager.isAlive(player.getUniqueId())) {
            return false;
        }

        return worldManager.isPvpAllowed(at);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void removeExpired(Map<UUID, Long> map, long now) {
        map.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static final class RootState {
        private final long expireAtMillis;
        private final float walkSpeed;
        private final float flySpeed;

        private RootState(long expireAtMillis, float walkSpeed, float flySpeed) {
            this.expireAtMillis = expireAtMillis;
            this.walkSpeed = walkSpeed;
            this.flySpeed = flySpeed;
        }
    }

    private static final class SkullRateWindow {
        private long windowStartMillis;
        private int spawnedCount;

        private SkullRateWindow(long windowStartMillis, int spawnedCount) {
            this.windowStartMillis = windowStartMillis;
            this.spawnedCount = spawnedCount;
        }
    }
}
