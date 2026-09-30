package com.pvpbot.command;

import com.pvpbot.config.BotConfig;
import com.pvpbot.entity.PvPBotEntity;
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

/** Vanilla chest-style PvPBot settings GUI. */
public class SettingsGuiHandler extends ScreenHandler {

    public static final int SLOT_REALISTIC_WEBBING = 11;
    public static final int SLOT_SAME_TICK_ATTACKS = 13;
    public static final int SLOT_LEDGE_LATCH       = 15;
    public static final int SLOT_LEAVE_ON_DEATH    = 17;

    private final PvPBotEntity bot;
    private final SimpleInventory inventory = new SimpleInventory(27);

    public SettingsGuiHandler(int syncId, PlayerInventory playerInventory, PvPBotEntity bot) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.bot = bot;

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

        BotConfig cfg = bot.getConfig();
        inventory.setStack(SLOT_REALISTIC_WEBBING, toggleItem(
                cfg.realisticWebbing, "Realistic webbing"));
        inventory.setStack(SLOT_SAME_TICK_ATTACKS, toggleItem(
                cfg.allowSameTickAttacks, "Same tick attacks"));
        inventory.setStack(SLOT_LEDGE_LATCH, toggleItem(
                cfg.ledgeLatchEnabled, "Ledge latch"));
        inventory.setStack(SLOT_LEAVE_ON_DEATH, toggleItem(
                cfg.leaveOnDeath, "Leave on death"));
    }

    private ItemStack toggleItem(boolean enabled, String name) {
        ItemStack stack = new ItemStack(enabled ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE);
        // Keep the title short: long custom names extend past a vanilla chest slot.
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

        BotConfig cfg = bot.getConfig();
        String setting;
        boolean enabled;
        switch (slotIndex) {
            case SLOT_REALISTIC_WEBBING -> {
                cfg.realisticWebbing = !cfg.realisticWebbing;
                setting = "Realistic webbing";
                enabled = cfg.realisticWebbing;
            }
            case SLOT_SAME_TICK_ATTACKS -> {
                cfg.allowSameTickAttacks = !cfg.allowSameTickAttacks;
                setting = "Same tick attacks";
                enabled = cfg.allowSameTickAttacks;
            }
            case SLOT_LEDGE_LATCH -> {
                cfg.ledgeLatchEnabled = !cfg.ledgeLatchEnabled;
                setting = "Ledge latch";
                enabled = cfg.ledgeLatchEnabled;
            }
            case SLOT_LEAVE_ON_DEATH -> {
                cfg.leaveOnDeath = !cfg.leaveOnDeath;
                setting = "Leave on death";
                enabled = cfg.leaveOnDeath;
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
