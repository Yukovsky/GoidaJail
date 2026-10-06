package com.goida.goidajail.compat;

import com.goida.goidajail.GoidaJail;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflective integration with Backpacked (NeoForge 1.21.1) and its addons (e.g. Backpacked Shells,
 * WetBackpacks, etc.). Captures equipped backpacks, clears them during jail time, and restores
 * them upon release.
 *
 * <p>Backpacked stores equipped backpacks in player synced data accessed via
 * {@code BackpackHelper.getBackpackStack(Player, int)} and {@code setBackpackStack(Player, ItemStack, int)}.
 * Each backpack {@link ItemStack} contains all its nested container contents, augments, unlocked slots,
 * and data-driven cosmetics (including cosmetics from addons).
 *
 * <p>Everything is accessed reflectively so GoidaJail compiles and runs cleanly with or without Backpacked.
 */
public final class BackpackedCompat {

    private static final boolean LOADED;
    private static Method getBackpackStack;           // static (Player, int) -> ItemStack
    private static Method setBackpackStack;           // static (Player, ItemStack, int) -> boolean
    private static Method getBackpacks;               // static (Player) -> NonNullList<ItemStack>
    private static Method removeAllBackpacks;         // static (Player) -> NonNullList<ItemStack>
    private static Method getMaxEquipable;            // static () -> int
    private static Method getBackpackInventoryCount;  // (BackpackedInventoryAccess) -> int
    private static Method getBackpackInventory;       // (BackpackedInventoryAccess, int) -> BackpackInventory
    private static Method saveItemsToStack;           // (BackpackInventory) -> void

    static {
        boolean ok = false;
        try {
            if (ModList.get().isLoaded("backpacked")) {
                Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
                Class<?> helperClass = Class.forName("com.mrcrayfish.backpacked.BackpackHelper");
                Class<?> mgmtClass = Class.forName("com.mrcrayfish.backpacked.inventory.ManagementInventory");

                getBackpackStack = helperClass.getMethod("getBackpackStack", playerClass, int.class);
                setBackpackStack = helperClass.getMethod("setBackpackStack", playerClass, ItemStack.class, int.class);
                getBackpacks = helperClass.getMethod("getBackpacks", playerClass);

                try {
                    removeAllBackpacks = helperClass.getMethod("removeAllBackpacks", playerClass);
                } catch (Throwable ignored) {}

                getMaxEquipable = mgmtClass.getMethod("getMaxEquipable");

                try {
                    Class<?> accessClass = Class.forName("com.mrcrayfish.backpacked.inventory.BackpackedInventoryAccess");
                    getBackpackInventoryCount = accessClass.getMethod("backpacked$GetBackpackInventoryCount");
                    getBackpackInventory = accessClass.getMethod("backpacked$GetBackpackInventory", int.class);
                    Class<?> invClass = Class.forName("com.mrcrayfish.backpacked.inventory.BackpackInventory");
                    saveItemsToStack = invClass.getMethod("saveItemsToStack");
                } catch (Throwable t) {
                    GoidaJail.LOGGER.debug("Backpacked inventory flush reflection not available (non-critical).", t);
                }

                ok = getBackpackStack != null && setBackpackStack != null && getMaxEquipable != null;
                if (!ok) {
                    GoidaJail.LOGGER.warn("Backpacked is present but required methods could not be reflected; "
                            + "backpack slots will NOT be confiscated/restored.");
                }
            }
        } catch (Throwable t) {
            GoidaJail.LOGGER.warn("Backpacked is present but its API could not be reflected; "
                    + "backpack slots will NOT be confiscated/restored.", t);
            ok = false;
        }
        LOADED = ok;
    }

    private BackpackedCompat() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    /**
     * Flushes any in-memory container changes into the backpack ItemStacks before reading them.
     */
    public static void flushInventories(ServerPlayer player) {
        if (!LOADED) return;
        try {
            if (getBackpackInventoryCount != null && getBackpackInventory != null && saveItemsToStack != null) {
                int count = (int) getBackpackInventoryCount.invoke(player);
                for (int i = 0; i < count; i++) {
                    Object inv = getBackpackInventory.invoke(player, i);
                    if (inv != null) {
                        saveItemsToStack.invoke(inv);
                    }
                }
            }
        } catch (Throwable t) {
            GoidaJail.LOGGER.warn("Failed to flush Backpacked inventories for {}", player.getGameProfile().getName(), t);
        }
    }

    /**
     * Maximum number of equipped backpack slots configured on the server (0 if mod absent).
     */
    public static int slotCount(ServerPlayer player) {
        if (!LOADED) return 0;
        try {
            if (getMaxEquipable != null) {
                return (int) getMaxEquipable.invoke(null);
            }
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Backpacked slotCount failed for {}", player.getGameProfile().getName(), t);
        }
        return 0;
    }

    /**
     * Gets the equipped backpack ItemStack at the given index.
     */
    public static ItemStack getStack(ServerPlayer player, int index) {
        if (!LOADED || index < 0) return ItemStack.EMPTY;
        try {
            if (getBackpackStack != null) {
                ItemStack stack = (ItemStack) getBackpackStack.invoke(null, player, index);
                return stack == null ? ItemStack.EMPTY : stack;
            }
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Backpacked getStack failed for {}", player.getGameProfile().getName(), t);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Sets the equipped backpack ItemStack at the given index.
     *
     * @return true if successfully set or false if failed.
     */
    public static boolean setStack(ServerPlayer player, int index, ItemStack stack) {
        if (!LOADED) return false;
        try {
            ItemStack toSet = stack == null ? ItemStack.EMPTY : stack;
            Boolean res = (Boolean) setBackpackStack.invoke(null, player, toSet, index);
            if (Boolean.TRUE.equals(res)) {
                return true;
            }
            // Fallback: directly update the backpacks NonNullList if setBackpackStack was rejected
            if (getBackpacks != null) {
                Object listObj = getBackpacks.invoke(null, player);
                if (listObj instanceof List<?> list && index >= 0 && index < list.size()) {
                    @SuppressWarnings("unchecked")
                    List<ItemStack> typedList = (List<ItemStack>) list;
                    typedList.set(index, toSet);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Backpacked setStack failed for {}", player.getGameProfile().getName(), t);
            return false;
        }
    }

    /**
     * Clears all equipped backpacks from the player.
     */
    public static void clear(ServerPlayer player) {
        if (!LOADED) return;
        try {
            if (removeAllBackpacks != null) {
                removeAllBackpacks.invoke(null, player);
                return;
            }
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Backpacked removeAllBackpacks failed for {}", player.getGameProfile().getName(), t);
        }
        int count = slotCount(player);
        for (int i = 0; i < count; i++) {
            setStack(player, i, ItemStack.EMPTY);
        }
    }
}
