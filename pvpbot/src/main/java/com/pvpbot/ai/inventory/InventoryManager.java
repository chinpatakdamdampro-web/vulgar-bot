package com.pvpbot.ai.inventory;

import com.pvpbot.entity.PvPBotEntity;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/**
 * InventoryManager — auto-equip armor/weapons, heal, manage shield/totem.
 *
 * New in this version:
 *  - Totem of Undying priority: ensureTotemInOffhand() moves a totem from
 *    inventory into offhand if one is available. Returns true if totem is active.
 *    Shield is only placed in offhand when no totem is present.
 *  - ensureShieldInMainhand() — for mace-block: swaps shield to main hand slot.
 *  - doPostTotemRecovery() — after totem pop: force-throws strength/speed potions
 *    and eats a golden apple if available, to restore the effect stack quickly.
 *  - swapBackTimer: now returns early from armor/weapon auto-equip while active
 *    so axe/mace swaps aren't immediately undone.
 *  - hasBreachMace() / equipBreachMace() for BREACH_SWAP combo.
 */
public class InventoryManager {

    private final PvPBotEntity bot;
    private int armorCheckCooldown = 0;

    /** When > 0, re-equip sword on next tick (after axe/mace hit). */
    private int swapBackTimer = 0;

    public InventoryManager(PvPBotEntity bot) {
        this.bot = bot;
    }

    // =========================================================================
    // Tick
    // =========================================================================

    public void tick() {
        // Swap back to sword after axe/mace hit
        if (swapBackTimer > 0) {
            swapBackTimer--;
            if (swapBackTimer == 0) {
                autoEquipBestWeapon(false);
            }
            // Don't run armor/weapon auto-equip while swap-back is pending
            return;
        }

        armorCheckCooldown--;
        if (armorCheckCooldown <= 0) {
            armorCheckCooldown = 40;
            autoEquipBestArmor();
            autoEquipBestWeapon(false);
        }
    }

    // =========================================================================
    // Totem management — highest priority offhand item
    // =========================================================================

    /**
     * Ensures a Totem of Undying is in the offhand if one exists in inventory.
     * Returns true if a totem is now in the offhand (bot is protected).
     * When true, shield should NOT be placed in offhand.
     */
    public boolean ensureTotemInOffhand() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        // Already have totem in offhand — great
        if (fp.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return true;

        var inv = fp.getInventory();
        // Search only hotbar (0-8) and main inventory (9-35). 
        // Slots 36-39 are armor, slot 40 is offhand — skip those.
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(Items.TOTEM_OF_UNDYING)) continue;
            // Swap totem into offhand (slot 40)
            ItemStack offhandCurrent = inv.getStack(40);
            inv.setStack(i, offhandCurrent);
            inv.setStack(40, stack);
            return true;
        }
        return false; // no totem found
    }

    // =========================================================================
    // Healing
    // =========================================================================

    public void checkAndHeal() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        if (fp.isUsingItem()) return;

        float health = fp.getHealth();

        if (health <= bot.getConfig().notchAppleThreshold) {
            if (tryEat(Items.ENCHANTED_GOLDEN_APPLE)) return;
        }
        if (health <= bot.getConfig().gappleThreshold) {
            if (tryEat(Items.GOLDEN_APPLE)) return;
            if (tryEat(Items.ENCHANTED_GOLDEN_APPLE)) return;
        }
        if (fp.getHungerManager().getFoodLevel() < bot.getConfig().hungerThreshold) {
            tryEatAnyFood();
        }
    }

    /**
     * Post-totem recovery: force-throw strength + speed potions immediately,
     * then eat a golden apple to restore HP quickly.
     * Called once by CombatController right after totem pop is detected.
     */
    public void doPostTotemRecovery() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();

        // Throw all beneficial splash/lingering potions from hotbar immediately
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            boolean isSplash    = stack.isOf(Items.SPLASH_POTION);
            boolean isLingering = stack.isOf(Items.LINGERING_POTION);
            if (!isSplash && !isLingering) continue;

            var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) continue;

            // Only throw beneficial potions — check by effect type key path
            boolean isBeneficial = false;
            for (var effect : contents.getEffects()) {
                var typeKey = effect.getEffectType().getKey();
                if (typeKey.isPresent()) {
                    String path = typeKey.get().getValue().getPath();
                    if (path.equals("strength") || path.equals("speed")
                            || path.equals("regeneration") || path.equals("fire_resistance")
                            || path.equals("absorption")) {
                        isBeneficial = true;
                        break;
                    }
                }
            }
            if (isBeneficial) {
                throwSplashPotion(fp, inv, i, stack);
            }
        }

        // Eat a golden apple (or notch apple) to start healing
        if (!tryEat(Items.ENCHANTED_GOLDEN_APPLE)) {
            tryEat(Items.GOLDEN_APPLE);
        }
    }

    // =========================================================================
    // Shield — offhand (SMP predict) and mainhand (mace block)
    // =========================================================================

    /**
     * Places shield in offhand for SMP shield prediction.
     * Only call this when no totem is present (checked by caller).
     */
    public void ensureShieldInOffhand() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        if (fp.getOffHandStack().isOf(Items.SHIELD)) return;
        // Don't displace a totem
        if (fp.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        var inv = fp.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(Items.SHIELD)) continue;
            ItemStack offhand = inv.getStack(40);
            inv.setStack(i, offhand);
            inv.setStack(40, stack);
            return;
        }
    }

    /**
     * Places shield in MAIN HAND (hotbar slot 8) for the mace-block behavior.
     * Slot 8 is used as it is the last hotbar slot and least likely to hold
     * a primary weapon. After blocking, scheduleSwapBackToSword() restores it.
     */
    public void ensureShieldInMainhand() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        // Check if already holding shield in any hotbar slot
        if (fp.getMainHandStack().isOf(Items.SHIELD)) return;

        var inv = fp.getInventory();
        // Check if shield is already in slot 8
        if (inv.getStack(8).isOf(Items.SHIELD)) {
            inv.selectedSlot = 8;
            return;
        }

        // Find shield in inventory (hotbar first, then main)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(Items.SHIELD)) continue;
            // Move to slot 8 — save what was there
            ItemStack displaced = inv.getStack(8);
            inv.setStack(i, displaced);
            inv.setStack(8, stack);
            inv.selectedSlot = 8;
            return;
        }
    }

    // =========================================================================
    // Weapon equip
    // =========================================================================

    /** Equip best axe for shield-disabling. Returns true if an axe was found. */
    public boolean equipBestAxe() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        int bestSlot  = -1;
        int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            int score = axeScore(stack);
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot < 0 || bestScore == 0) return false;
        swapToHotbar(inv, bestSlot);
        return true;
    }

    /** Re-equips best weapon after axe/mace hit (3-tick delay). */
    public void scheduleSwapBackToSword() {
        swapBackTimer = 3;
    }

    /**
     * Returns true if the bot has a Mace with Breach enchantment anywhere
     * in its inventory.
     */
    public boolean hasBreachMace() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.MACE) && getEnchantLevel(stack, "breach") > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Equips the best Breach mace from inventory into the hotbar.
     * Returns true if a breach mace was found and equipped.
     */
    public boolean equipBreachMace() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        int bestSlot  = -1;
        int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(Items.MACE)) continue;
            int breachLevel = getEnchantLevel(stack, "breach");
            if (breachLevel == 0) continue;
            int score = 100 + breachLevel * 10 + getEnchantLevel(stack, "density") * 5;
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot < 0) return false;
        swapToHotbar(inv, bestSlot);
        return true;
    }

    public void equipBestWeapon(boolean preferAxe) {
        autoEquipBestWeapon(preferAxe);
    }

    private void autoEquipBestWeapon(boolean preferAxe) {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        int bestSlot  = -1;
        int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            int score = weaponScore(inv.getStack(i), preferAxe);
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot >= 0 && bestSlot != inv.selectedSlot) {
            swapToHotbar(inv, bestSlot);
        }
    }

    // =========================================================================
    // Armor
    // =========================================================================

    private void autoEquipBestArmor() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        checkArmorSlot(fp, inv, EquipmentSlot.HEAD);
        checkArmorSlot(fp, inv, EquipmentSlot.CHEST);
        checkArmorSlot(fp, inv, EquipmentSlot.LEGS);
        checkArmorSlot(fp, inv, EquipmentSlot.FEET);
    }

    private void checkArmorSlot(ServerPlayerEntity fp,
                                 net.minecraft.entity.player.PlayerInventory inv,
                                 EquipmentSlot slot) {
        ItemStack current = fp.getEquippedStack(slot);
        int currentScore  = armorScore(current);
        for (int i = 0; i < 36; i++) {
            ItemStack candidate = inv.getStack(i);
            if (!isArmorForSlot(candidate, slot)) continue;
            int score = armorScore(candidate);
            if (score > currentScore) {
                inv.setStack(i, current);
                fp.equipStack(slot, candidate);
                current      = candidate;
                currentScore = score;
            }
        }
    }

    // =========================================================================
    // Scoring
    // =========================================================================

    private int axeScore(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        int base = 0;
        if      (item == Items.NETHERITE_AXE) base = 100;
        else if (item == Items.DIAMOND_AXE)   base = 85;
        else if (item == Items.IRON_AXE)      base = 60;
        else if (item == Items.STONE_AXE)     base = 35;
        else if (item == Items.WOODEN_AXE)    base = 10;
        if (base == 0) return 0;
        base += getEnchantLevel(stack, "sharpness") * 4;
        return base;
    }

    private int weaponScore(ItemStack stack, boolean preferAxe) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        int base = 0;
        if (preferAxe) {
            base = axeScore(stack);
            if (base > 0) return base;
        }
        if      (item == Items.NETHERITE_SWORD) base = 100;
        else if (item == Items.DIAMOND_SWORD)   base = 85;
        else if (item == Items.IRON_SWORD)      base = 60;
        else if (item == Items.STONE_SWORD)     base = 35;
        else if (item == Items.WOODEN_SWORD)    base = 10;
        else if (item == Items.NETHERITE_AXE)   base = 88;
        else if (item == Items.DIAMOND_AXE)     base = 75;
        else if (item == Items.IRON_AXE)        base = 45;
        else if (item == Items.MACE)            base = 70;
        if (base == 0) return 0;
        base += getEnchantLevel(stack, "sharpness")  * 5;
        base += getEnchantLevel(stack, "smite")       * 3;
        base += getEnchantLevel(stack, "fire_aspect") * 4;
        if (item == Items.MACE) base += getEnchantLevel(stack, "density") * 6;
        return base;
    }

    private int armorScore(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        int base = materialArmorScore(stack.getItem());
        if (base == 0) return 0;
        base += getEnchantLevel(stack, "protection") * 8;
        return base;
    }

    private int materialArmorScore(Item item) {
        if (item == Items.NETHERITE_HELMET   || item == Items.NETHERITE_CHESTPLATE ||
            item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS)      return 100;
        if (item == Items.DIAMOND_HELMET     || item == Items.DIAMOND_CHESTPLATE   ||
            item == Items.DIAMOND_LEGGINGS   || item == Items.DIAMOND_BOOTS)        return 80;
        if (item == Items.IRON_HELMET        || item == Items.IRON_CHESTPLATE      ||
            item == Items.IRON_LEGGINGS      || item == Items.IRON_BOOTS)           return 55;
        if (item == Items.CHAINMAIL_HELMET   || item == Items.CHAINMAIL_CHESTPLATE ||
            item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS)      return 40;
        if (item == Items.GOLDEN_HELMET      || item == Items.GOLDEN_CHESTPLATE    ||
            item == Items.GOLDEN_LEGGINGS    || item == Items.GOLDEN_BOOTS)         return 25;
        if (item == Items.LEATHER_HELMET     || item == Items.LEATHER_CHESTPLATE   ||
            item == Items.LEATHER_LEGGINGS   || item == Items.LEATHER_BOOTS)        return 10;
        return 0;
    }

    private boolean isArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        return switch (slot) {
            case HEAD  -> item == Items.NETHERITE_HELMET    || item == Items.DIAMOND_HELMET    ||
                          item == Items.IRON_HELMET         || item == Items.CHAINMAIL_HELMET  ||
                          item == Items.GOLDEN_HELMET       || item == Items.LEATHER_HELMET;
            case CHEST -> item == Items.NETHERITE_CHESTPLATE|| item == Items.DIAMOND_CHESTPLATE||
                          item == Items.IRON_CHESTPLATE     || item == Items.CHAINMAIL_CHESTPLATE||
                          item == Items.GOLDEN_CHESTPLATE   || item == Items.LEATHER_CHESTPLATE;
            case LEGS  -> item == Items.NETHERITE_LEGGINGS  || item == Items.DIAMOND_LEGGINGS  ||
                          item == Items.IRON_LEGGINGS       || item == Items.CHAINMAIL_LEGGINGS||
                          item == Items.GOLDEN_LEGGINGS     || item == Items.LEATHER_LEGGINGS;
            case FEET  -> item == Items.NETHERITE_BOOTS     || item == Items.DIAMOND_BOOTS     ||
                          item == Items.IRON_BOOTS          || item == Items.CHAINMAIL_BOOTS   ||
                          item == Items.GOLDEN_BOOTS        || item == Items.LEATHER_BOOTS;
            default    -> false;
        };
    }

    // =========================================================================
    // Food / eating
    // =========================================================================

    private boolean tryEat(Item foodItem) {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(foodItem)) continue;
            if (i < 9) {
                inv.selectedSlot = i;
            } else {
                ItemStack hotbarItem = inv.getStack(0);
                inv.setStack(0, stack);
                inv.setStack(i, hotbarItem);
                inv.selectedSlot = 0;
            }
            fp.setCurrentHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void tryEatAnyFood() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.get(DataComponentTypes.FOOD) != null) {
                inv.selectedSlot = i;
                fp.setCurrentHand(Hand.MAIN_HAND);
                return;
            }
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private void swapToHotbar(net.minecraft.entity.player.PlayerInventory inv, int slot) {
        if (slot < 9) {
            inv.selectedSlot = slot;
        } else {
            ItemStack weapon  = inv.getStack(slot);
            ItemStack hotbar0 = inv.getStack(0);
            inv.setStack(slot, hotbar0);
            inv.setStack(0, weapon);
            inv.selectedSlot = 0;
        }
    }

    private int getEnchantLevel(ItemStack stack, String enchId) {
        var enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments == null) return 0;
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.getKey().isPresent()) {
                String id = entry.getKey().get().getValue().getPath();
                if (id.equals(enchId)) return enchantments.getLevel(entry);
            }
        }
        return 0;
    }

    // =========================================================================
    // Cobweb placement
    // =========================================================================

    /**
     * Returns true if the bot has at least 2 cobwebs in its inventory.
     * Needs 2 because the cobweb bubble uses one at feet AND one at head.
     */
    public boolean hasCobwebs(int minimum) {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.COBWEB)) {
                count += stack.getCount();
                if (count >= minimum) return true;
            }
        }
        return false;
    }

    /**
     * Places a cobweb at the given world position by setting the block directly.
     * Uses one cobweb from the bot's inventory. Returns false if no cobweb found.
     */
    public boolean placeCobwebAt(BlockPos pos) {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var world = (ServerWorld) fp.getWorld();
        var inv = fp.getInventory();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(Items.COBWEB)) continue;

            var existingState = world.getBlockState(pos);
            if (!existingState.isAir()) return false;

            world.setBlockState(pos, Blocks.COBWEB.getDefaultState());

            stack.decrement(1);
            if (stack.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }
    // =========================================================================

    /**
     * Scans hotbar for potions whose effect has expired and uses them.
     *  - Splash / lingering potions: thrown as a projectile.
     *  - Drinkable potions: consumed via setCurrentHand.
     */
    public void throwPotionIfNeeded() {
        ServerPlayerEntity fp = bot.getFakePlayer();
        var inv = fp.getInventory();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            boolean isSplash    = stack.isOf(Items.SPLASH_POTION);
            boolean isLingering = stack.isOf(Items.LINGERING_POTION);
            boolean isDrinkable = stack.isOf(Items.POTION);

            if (!isSplash && !isLingering && !isDrinkable) continue;

            var potionContents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (potionContents == null) continue;

            boolean needsPotion = false;
            for (var effect : potionContents.getEffects()) {
                var activeEffect = fp.getStatusEffect(effect.getEffectType());
                if (activeEffect == null || activeEffect.getDuration() < 200) {
                    needsPotion = true;
                    break;
                }
            }

            if (!needsPotion) continue;

            if (isSplash || isLingering) {
                throwSplashPotion(fp, inv, i, stack);
            } else {
                inv.selectedSlot = i;
                fp.setCurrentHand(Hand.MAIN_HAND);
            }
            return;
        }
    }

    /**
     * Spawns a PotionEntity aimed slightly downward at the bot's feet.
     * Consumes one item from the inventory slot.
     */
    private void throwSplashPotion(ServerPlayerEntity fp,
                                    net.minecraft.entity.player.PlayerInventory inv,
                                    int slot,
                                    ItemStack stack) {
        ServerWorld world = (ServerWorld) fp.getWorld();
        // Aim slightly downward (positive pitch = looking down in MC coords)
        // so the splash lands at/near the bot's feet.
        // setVelocity(shooter, pitch, yaw, roll=0, speed, divergence)
        float yaw   = fp.getYaw();
        float pitch = 30f; // 30° downward arc — lands ~1-2 blocks in front

        PotionEntity potion = new PotionEntity(world, fp);
        potion.setItem(stack.copy());
        potion.setVelocity(fp, pitch, yaw, 0.0f, 0.5f, 1.0f);
        world.spawnEntity(potion);

        stack.decrement(1);
        if (stack.isEmpty()) inv.setStack(slot, ItemStack.EMPTY);
    }
}
