package com.pvpbot.command;

import com.pvpbot.config.BotConfig;
import com.pvpbot.config.PvPBotConfigFile;
import com.pvpbot.entity.BotManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Global PvPBot settings GUI.
 *
 * Reads and writes PvPBotConfigFile (the persistent global config) instead of
 * a single bot's BotConfig. Every toggle is immediately propagated to all
 * currently live bots via BotManager so the change takes effect without a
 * restart, and is saved to disk so newly spawned bots also pick it up.
 *
 * The GUI is opened with /pb gui (no bot argument) because these settings
 * apply server-wide, not per-bot.
 */
public class SettingsGuiHandler extends ScreenHandler {

    public static final int SLOT_REALISTIC_WEBBING = 11;
    public static final int SLOT_SAME_TICK_ATTACKS = 13;
    public static final int SLOT_LEDGE_LATCH       = 15;
    public static final int SLOT_LEAVE_ON_DEATH    = 17;

    private final SimpleInventory inventory = new SimpleInventory(27);

    public SettingsGuiHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                addSlot(new LockedSlot(inventory, index, 8 + col * 18, 18 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        refreshItems();
    }

    private void refreshItems() {
        for (int i = 0; i < inventory.size(); i++) {
            inventory.setStack(i, named(Items.GRAY_STAINED_GLASS_PANE.getDefaultStack(), " "));
        }

        // Read from global config, not a per-bot BotConfig.
        // We use a representative live bot for settings that aren't yet in
        // PvPBotConfigFile (realisticWebbing, sameTickAttacks, ledgeLatch)
        // but leaveOnDeath always comes from the global file.
        BotConfig representative = getRepresentativeConfig();
        PvPBotConfigFile global = PvPBotConfigFile.getInstance();

        inventory.setStack(SLOT_REALISTIC_WEBBING, toggleItem(
                representative.realisticWebbing, "Realistic webbing"));
        inventory.setStack(SLOT_SAME_TICK_ATTACKS, toggleItem(
                representative.allowSameTickAttacks, "Same tick attacks"));
        inventory.setStack(SLOT_LEDGE_LATCH, toggleItem(
                representative.ledgeLatchEnabled, "Ledge latch"));
        inventory.setStack(SLOT_LEAVE_ON_DEATH, toggleItem(
                global.getLeaveOnDeath(), "Leave on death"));
    }

    /** Returns the config of any live bot, or a default config as fallback. */
    private BotConfig getRepresentativeConfig() {
        return BotManager.getInstance().getAll().stream()
                .findFirst()
                .map(b -> b.getConfig())
                .orElseGet(PvPBotConfigFile.getInstance()::buildDefaultConfig);
    }

    private ItemStack toggleItem(boolean enabled, String name) {
        ItemStack stack = new ItemStack(enabled ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE);
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                Text.literal((enabled ? "§a§l" : "§c§l") + name + ": "
                        + (enabled ? "ON" : "OFF")));
        return stack;
    }

    private ItemStack named(ItemStack stack, String name) {
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
        if (slotIndex < 0) return;
        if (slotIndex >= inventory.size()) {
            super.onSlotClick(slotIndex, button, actionType, player);
            return;
        }

        PvPBotConfigFile global = PvPBotConfigFile.getInstance();
        String setting;
        boolean enabled;

        switch (slotIndex) {
            case SLOT_REALISTIC_WEBBING -> {
                boolean next = !getRepresentativeConfig().realisticWebbing;
                BotManager.getInstance().getAll().forEach(b -> b.getConfig().realisticWebbing = next);
                setting = "Realistic webbing";
                enabled = next;
            }
            case SLOT_SAME_TICK_ATTACKS -> {
                boolean next = !getRepresentativeConfig().allowSameTickAttacks;
                BotManager.getInstance().getAll().forEach(b -> b.getConfig().allowSameTickAttacks = next);
                setting = "Same tick attacks";
                enabled = next;
            }
            case SLOT_LEDGE_LATCH -> {
                boolean next = !getRepresentativeConfig().ledgeLatchEnabled;
                BotManager.getInstance().getAll().forEach(b -> b.getConfig().ledgeLatchEnabled = next);
                setting = "Ledge latch";
                enabled = next;
            }
            case SLOT_LEAVE_ON_DEATH -> {
                // leaveOnDeath is global — write to file AND propagate to all live bots.
                boolean next = !global.getLeaveOnDeath();
                global.setLeaveOnDeath(next);   // saves to disk immediately
                BotManager.getInstance().getAll().forEach(b -> b.getConfig().leaveOnDeath = next);
                setting = "Leave on death";
                enabled = next;
            }
            default -> { return; }
        }

        refreshItems();
        serverPlayer.currentScreenHandler.sendContentUpdates();
        serverPlayer.sendMessage(Text.literal("§6§lPvPBot §8» " + (enabled ? "§a§l" : "§c§l")
                + setting + " " + (enabled ? "ON" : "OFF")), false);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    private static class LockedSlot extends Slot {
        LockedSlot(SimpleInventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) { return false; }

        @Override
        public boolean canTakeItems(PlayerEntity playerEntity) { return false; }
    }
}