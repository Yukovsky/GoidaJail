package com.goida.goidajail.jail;

import com.goida.goidajail.compat.BackpackedCompat;
import com.goida.goidajail.compat.CosmeticArmorCompat;
import com.goida.goidajail.compat.CuriosCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A jailed player's confiscated inventory, stored per-slot so it can be shown and edited in a
 * GUI and so untaken items return to their exact original slots on release. Lives in
 * {@link JailSavedData} (level data), which makes it accessible whether the prisoner is online
 * or offline. Replaces the old opaque-blob {@code InventorySnapshot}.
 */
public final class ConfiscatedInventory {

    /** Vanilla inventory size via {@code Inventory#getItem}: 0–35 main, 36–39 armor, 40 offhand. */
    public static final int VANILLA_SIZE = 41;

    public enum Section { VANILLA, BACKPACKED, CURIOS, COSMETIC }

    /** A logical slot: a section plus its index within that section. */
    public record Origin(Section section, int index) {}

    private final ItemStack[] vanilla;
    private final ItemStack[] backpacked;
    private final ItemStack[] curios;
    private final ItemStack[] cosmetic;

    public ConfiscatedInventory(int curiosSize, int cosmeticSize, int backpackedSize) {
        this.vanilla = filled(VANILLA_SIZE);
        this.backpacked = filled(Math.max(0, backpackedSize));
        this.curios = filled(Math.max(0, curiosSize));
        this.cosmetic = filled(Math.max(0, cosmeticSize));
    }

    public ConfiscatedInventory(int curiosSize, int cosmeticSize) {
        this(curiosSize, cosmeticSize, 0);
    }

    private static ItemStack[] filled(int n) {
        ItemStack[] a = new ItemStack[n];
        Arrays.fill(a, ItemStack.EMPTY);
        return a;
    }

    // ---- Capture / clear / restore --------------------------------------------------------

    /** Reads every slot (copies) WITHOUT clearing the live player — used at arrest. */
    public static ConfiscatedInventory captureNoClear(ServerPlayer player) {
        if (BackpackedCompat.isLoaded()) {
            BackpackedCompat.flushInventories(player);
        }
        int curiosSize = CuriosCompat.isLoaded() ? CuriosCompat.slotCount(player) : 0;
        int cosmeticSize = CosmeticArmorCompat.isLoaded() ? CosmeticArmorCompat.slotCount() : 0;
        int backpackedSize = BackpackedCompat.isLoaded() ? BackpackedCompat.slotCount(player) : 0;
        ConfiscatedInventory c = new ConfiscatedInventory(curiosSize, cosmeticSize, backpackedSize);
        for (int i = 0; i < VANILLA_SIZE; i++) {
            c.vanilla[i] = player.getInventory().getItem(i).copy();
        }
        for (int i = 0; i < backpackedSize; i++) {
            c.backpacked[i] = BackpackedCompat.getStack(player, i).copy();
        }
        for (int i = 0; i < curiosSize; i++) {
            c.curios[i] = CuriosCompat.getStack(player, i).copy();
        }
        for (int i = 0; i < cosmeticSize; i++) {
            c.cosmetic[i] = CosmeticArmorCompat.getStack(player, i).copy();
        }
        return c;
    }

    /** Empties the live player's vanilla, Backpacked, Curios and cosmetic slots. */
    public static void clearLive(ServerPlayer player) {
        player.getInventory().clearContent();
        if (BackpackedCompat.isLoaded()) {
            BackpackedCompat.clear(player);
        }
        if (CuriosCompat.isLoaded()) {
            int n = CuriosCompat.slotCount(player);
            for (int i = 0; i < n; i++) CuriosCompat.setStack(player, i, ItemStack.EMPTY);
        }
        if (CosmeticArmorCompat.isLoaded()) {
            int n = CosmeticArmorCompat.slotCount();
            for (int i = 0; i < n; i++) CosmeticArmorCompat.setStack(player, i, ItemStack.EMPTY);
        }
    }

    /** Writes every non-empty stored stack back to its original slot on the live player. */
    public void restore(ServerPlayer player) {
        for (int i = 0; i < VANILLA_SIZE; i++) {
            if (!vanilla[i].isEmpty()) player.getInventory().setItem(i, vanilla[i]);
        }
        if (BackpackedCompat.isLoaded()) {
            for (int i = 0; i < backpacked.length; i++) {
                if (!backpacked[i].isEmpty()) {
                    boolean ok = BackpackedCompat.setStack(player, i, backpacked[i]);
                    if (!ok) {
                        if (!player.getInventory().add(backpacked[i])) {
                            player.drop(backpacked[i], false);
                        }
                    }
                }
            }
        } else {
            for (ItemStack stack : backpacked) {
                if (!stack.isEmpty()) {
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
            }
        }
        if (CuriosCompat.isLoaded()) {
            for (int i = 0; i < curios.length; i++) {
                if (!curios[i].isEmpty()) CuriosCompat.setStack(player, i, curios[i]);
            }
        }
        if (CosmeticArmorCompat.isLoaded()) {
            for (int i = 0; i < cosmetic.length; i++) {
                if (!cosmetic[i].isEmpty()) CosmeticArmorCompat.setStack(player, i, cosmetic[i]);
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    // ---- GUI layout access ----------------------------------------------------------------

    /** Ordered logical slots: vanilla, then Backpacked, then Curios, then cosmetic. */
    public List<Origin> buildLayout() {
        List<Origin> layout = new ArrayList<>(VANILLA_SIZE + backpacked.length + curios.length + cosmetic.length);
        for (int i = 0; i < VANILLA_SIZE; i++) layout.add(new Origin(Section.VANILLA, i));
        for (int i = 0; i < backpacked.length; i++) layout.add(new Origin(Section.BACKPACKED, i));
        for (int i = 0; i < curios.length; i++) layout.add(new Origin(Section.CURIOS, i));
        for (int i = 0; i < cosmetic.length; i++) layout.add(new Origin(Section.COSMETIC, i));
        return layout;
    }

    public ItemStack get(Origin o) {
        ItemStack[] a = arrayFor(o.section());
        return (o.index() >= 0 && o.index() < a.length) ? a[o.index()] : ItemStack.EMPTY;
    }

    public void set(Origin o, ItemStack stack) {
        ItemStack[] a = arrayFor(o.section());
        if (o.index() >= 0 && o.index() < a.length) {
            a[o.index()] = stack == null ? ItemStack.EMPTY : stack;
        }
    }

    private ItemStack[] arrayFor(Section s) {
        return switch (s) {
            case VANILLA -> vanilla;
            case BACKPACKED -> backpacked;
            case CURIOS -> curios;
            case COSMETIC -> cosmetic;
        };
    }

    // ---- Serialization --------------------------------------------------------------------

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("curiosSize", curios.length);
        tag.putInt("cosmeticSize", cosmetic.length);
        tag.putInt("backpackedSize", backpacked.length);
        tag.put("vanilla", sectionToNbt(vanilla, provider));
        tag.put("backpacked", sectionToNbt(backpacked, provider));
        tag.put("curios", sectionToNbt(curios, provider));
        tag.put("cosmetic", sectionToNbt(cosmetic, provider));
        return tag;
    }

    public static ConfiscatedInventory load(CompoundTag tag, HolderLookup.Provider provider) {
        int curiosSize = tag.getInt("curiosSize");
        int cosmeticSize = tag.getInt("cosmeticSize");
        int backpackedSize = tag.getInt("backpackedSize");
        ConfiscatedInventory c = new ConfiscatedInventory(curiosSize, cosmeticSize, backpackedSize);
        sectionFromNbt(c.vanilla, tag.getList("vanilla", Tag.TAG_COMPOUND), provider);
        if (tag.contains("backpacked", Tag.TAG_LIST)) {
            sectionFromNbt(c.backpacked, tag.getList("backpacked", Tag.TAG_COMPOUND), provider);
        }
        sectionFromNbt(c.curios, tag.getList("curios", Tag.TAG_COMPOUND), provider);
        sectionFromNbt(c.cosmetic, tag.getList("cosmetic", Tag.TAG_COMPOUND), provider);
        return c;
    }

    private static ListTag sectionToNbt(ItemStack[] arr, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null || arr[i].isEmpty()) continue;
            CompoundTag e = new CompoundTag();
            e.putInt("Slot", i);
            e.put("item", arr[i].save(provider));
            list.add(e);
        }
        return list;
    }

    private static void sectionFromNbt(ItemStack[] arr, ListTag list, HolderLookup.Provider provider) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int slot = e.getInt("Slot");
            if (slot < 0 || slot >= arr.length) continue;
            arr[slot] = ItemStack.parseOptional(provider, e.getCompound("item"));
        }
    }
}
