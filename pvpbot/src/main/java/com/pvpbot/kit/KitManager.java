package com.pvpbot.kit;

import com.google.gson.*;
import com.pvpbot.PvPBotMod;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * KitManager — saves and loads full player inventory kits to JSON.
 *
 * API notes for MC 1.21.1 Yarn:
 *  - server.getRunDirectory() returns java.nio.file.Path directly (NOT File).
 *  - ItemStack.encode(RegistryWrapper.WrapperLookup) returns NbtElement (1-arg).
 *  - ItemStack.fromNbt(RegistryWrapper.WrapperLookup, NbtCompound) returns Optional<ItemStack>.
 *  - player.getRegistryManager() returns DynamicRegistryManager which
 *    implements RegistryWrapper.WrapperLookup.
 *
 * Saves:
 *  - Hotbar + main inventory (slots 0–35)
 *  - Armor (head, chest, legs, feet)
 *  - Offhand (slot 40)
 *
 * Kits stored in: <server_dir>/config/pvpbot/kits/<name>.json
 */
public class KitManager {

    private static final KitManager INSTANCE = new KitManager();
    public static KitManager getInstance() { return INSTANCE; }

    private Path kitsDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private KitManager() {}

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    public void init(MinecraftServer server) {
        // getRunDirectory() returns Path in 1.21.1 — no .toPath() needed
        kitsDir = server.getRunDirectory().resolve("config/pvpbot/kits");
        try {
            Files.createDirectories(kitsDir);
            PvPBotMod.LOGGER.info("[PvPBot] Kits directory: {}", kitsDir.toAbsolutePath());
        } catch (IOException e) {
            PvPBotMod.LOGGER.error("[PvPBot] Failed to create kits directory: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Saves a player's full inventory as a named kit.
     * @return null on success, error string on failure.
     */
    public String saveKit(String kitName, ServerPlayerEntity player) {
        if (kitsDir == null) return "KitManager not initialized (server not started yet).";
        if (!isValidName(kitName)) return "Invalid kit name. Use only letters, numbers, _ or -.";

        // RegistryWrapper.WrapperLookup is what encode() needs
        RegistryWrapper.WrapperLookup reg = player.getRegistryManager();
        JsonObject root = new JsonObject();
        root.addProperty("savedBy", player.getName().getString());
        root.addProperty("savedAt", System.currentTimeMillis());

        // Main inventory slots 0–35 (hotbar = 0–8, main = 9–35)
        JsonArray mainInv = new JsonArray();
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            JsonObject slotObj = new JsonObject();
            slotObj.addProperty("slot", i);
            if (!stack.isEmpty()) {
                // encode(WrapperLookup) returns NbtElement — call .toString() directly
                slotObj.addProperty("nbt", stack.encode(reg).toString());
            }
            mainInv.add(slotObj);
        }
        root.add("mainInventory", mainInv);

        // Armor slots
        JsonObject armor = new JsonObject();
        armor.addProperty("head",  encodeStack(player.getEquippedStack(EquipmentSlot.HEAD),  reg));
        armor.addProperty("chest", encodeStack(player.getEquippedStack(EquipmentSlot.CHEST), reg));
        armor.addProperty("legs",  encodeStack(player.getEquippedStack(EquipmentSlot.LEGS),  reg));
        armor.addProperty("feet",  encodeStack(player.getEquippedStack(EquipmentSlot.FEET),  reg));
        root.add("armor", armor);

        // Offhand (slot 40)
        root.addProperty("offhand", encodeStack(player.getOffHandStack(), reg));

        try {
            Files.writeString(kitsDir.resolve(kitName + ".json"), gson.toJson(root));
            return null; // success
        } catch (IOException e) {
            return "Failed to write kit file: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Give
    // -------------------------------------------------------------------------

    /**
     * Gives a saved kit to a player (clears their inventory first).
     * @return null on success, error string on failure.
     */
    public String giveKit(String kitName, ServerPlayerEntity player) {
        if (kitsDir == null) return "KitManager not initialized.";
        if (!isValidName(kitName)) return "Invalid kit name.";

        Path file = kitsDir.resolve(kitName + ".json");
        if (!Files.exists(file)) return "Kit '" + kitName + "' not found.";

        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            RegistryWrapper.WrapperLookup reg = player.getRegistryManager();

            // Clear inventory
            player.getInventory().clear();

            // Main inventory
            JsonArray mainInv = root.getAsJsonArray("mainInventory");
            for (JsonElement el : mainInv) {
                JsonObject slotObj = el.getAsJsonObject();
                int slot = slotObj.get("slot").getAsInt();
                if (slotObj.has("nbt")) {
                    ItemStack stack = decodeStack(slotObj.get("nbt").getAsString(), reg);
                    if (!stack.isEmpty()) player.getInventory().setStack(slot, stack);
                }
            }

            // Armor
            JsonObject armor = root.getAsJsonObject("armor");
            player.equipStack(EquipmentSlot.HEAD,  decodeStack(armor.get("head").getAsString(),  reg));
            player.equipStack(EquipmentSlot.CHEST, decodeStack(armor.get("chest").getAsString(), reg));
            player.equipStack(EquipmentSlot.LEGS,  decodeStack(armor.get("legs").getAsString(),  reg));
            player.equipStack(EquipmentSlot.FEET,  decodeStack(armor.get("feet").getAsString(),  reg));

            // Offhand
            if (root.has("offhand") && !root.get("offhand").getAsString().isEmpty()) {
                ItemStack offhand = decodeStack(root.get("offhand").getAsString(), reg);
                player.getInventory().setStack(40, offhand);
            }

            player.getInventory().markDirty();
            return null; // success

        } catch (Exception e) {
            PvPBotMod.LOGGER.error("[PvPBot] giveKit '{}' error: {}", kitName, e.getMessage(), e);
            return "Error loading kit: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // List / exists
    // -------------------------------------------------------------------------

    public List<String> listKits() {
        if (kitsDir == null || !Files.exists(kitsDir)) return List.of();
        try {
            List<String> names = new ArrayList<>();
            Files.list(kitsDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    String fname = p.getFileName().toString();
                    names.add(fname.substring(0, fname.length() - 5));
                });
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    public boolean kitExists(String name) {
        if (kitsDir == null) return false;
        return Files.exists(kitsDir.resolve(name + ".json"));
    }

    // -------------------------------------------------------------------------
    // Encode / decode helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes an ItemStack to an NBT string.
     * Returns empty string for empty stacks.
     */
    private String encodeStack(ItemStack stack, RegistryWrapper.WrapperLookup reg) {
        if (stack.isEmpty()) return "";
        // encode(WrapperLookup) -> NbtElement — .toString() gives the NBT string
        return stack.encode(reg).toString();
    }

    /**
     * Decodes an ItemStack from an NBT string.
     * Returns ItemStack.EMPTY for empty/invalid strings.
     */
    private ItemStack decodeStack(String nbtStr, RegistryWrapper.WrapperLookup reg) {
        if (nbtStr == null || nbtStr.isEmpty()) return ItemStack.EMPTY;
        try {
            NbtCompound nbt = StringNbtReader.parse(nbtStr);
            // fromNbt(WrapperLookup, NbtCompound) -> Optional<ItemStack> in 1.21.1
            return ItemStack.fromNbt(reg, nbt).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            PvPBotMod.LOGGER.warn("[PvPBot] Failed to decode item: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private boolean isValidName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_\\-]+") && name.length() <= 32;
    }
}
