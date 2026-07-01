package com.goida.goidajail.compat;

import com.goida.goidajail.GoidaJail;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reflective integration with Curios (NeoForge 1.21.1). Uses
 * {@code CuriosApi.getCuriosInventory(LivingEntity)} →
 * {@code ICuriosItemHandler#saveInventory(boolean)} / {@code #loadInventory(ListTag)} so the
 * mod compiles and runs with or without Curios installed.
 */
public final class CuriosCompat {

    private static final boolean LOADED;
    private static Method getCuriosInventory; // static (LivingEntity) -> Optional<ICuriosItemHandler>
    private static Method saveInventory;      // (boolean) -> ListTag
    private static Method loadInventory;      // (ListTag) -> void
    private static Method getEquippedCurios;  // () -> IItemHandlerModifiable (flat view of all slots)

    static {
        boolean ok = false;
        try {
            if (ModList.get().isLoaded("curios")) {
                Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                getCuriosInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
                Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
                saveInventory = handler.getMethod("saveInventory", boolean.class);
                loadInventory = handler.getMethod("loadInventory", ListTag.class);
                getEquippedCurios = handler.getMethod("getEquippedCurios");
                ok = true;
            }
        } catch (Throwable t) {
            GoidaJail.LOGGER.warn("Curios is present but its API could not be reflected; "
                    + "Curios slots will NOT be confiscated/restored.", t);
            ok = false;
        }
        LOADED = ok;
    }

    private CuriosCompat() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    /** Saves all Curios slots to NBT and clears them on the player. */
    @Nullable
    public static ListTag saveAndClear(ServerPlayer player) throws Exception {
        Optional<?> opt = (Optional<?>) getCuriosInventory.invoke(null, player);
        if (opt.isEmpty()) {
            return null;
        }
        return (ListTag) saveInventory.invoke(opt.get(), true);
    }

    /** Restores previously saved Curios slots. */
    public static void load(ServerPlayer player, ListTag data) throws Exception {
        Optional<?> opt = (Optional<?>) getCuriosInventory.invoke(null, player);
        if (opt.isEmpty()) {
            return;
        }
        loadInventory.invoke(opt.get(), data);
    }

    // ---- Per-slot access (for the confiscation GUI) ---------------------------------------

    /** Flat modifiable view of all equipped curio slots, or {@code null}. */
    @Nullable
    private static IItemHandlerModifiable equipped(ServerPlayer player) throws Exception {
        Optional<?> opt = (Optional<?>) getCuriosInventory.invoke(null, player);
        if (opt.isEmpty()) {
            return null;
        }
        return (IItemHandlerModifiable) getEquippedCurios.invoke(opt.get());
    }

    /** Number of equipped curio slots (0 if Curios absent or unavailable). */
    public static int slotCount(ServerPlayer player) {
        if (!LOADED) return 0;
        try {
            IItemHandlerModifiable h = equipped(player);
            return h == null ? 0 : h.getSlots();
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Curios slotCount failed for {}", player.getGameProfile().getName(), t);
            return 0;
        }
    }

    public static ItemStack getStack(ServerPlayer player, int index) {
        if (!LOADED) return ItemStack.EMPTY;
        try {
            IItemHandlerModifiable h = equipped(player);
            if (h == null || index < 0 || index >= h.getSlots()) return ItemStack.EMPTY;
            return h.getStackInSlot(index);
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Curios getStack failed for {}", player.getGameProfile().getName(), t);
            return ItemStack.EMPTY;
        }
    }

    public static void setStack(ServerPlayer player, int index, ItemStack stack) {
        if (!LOADED) return;
        try {
            IItemHandlerModifiable h = equipped(player);
            if (h == null || index < 0 || index >= h.getSlots()) return;
            h.setStackInSlot(index, stack);
        } catch (Throwable t) {
            GoidaJail.LOGGER.error("Curios setStack failed for {}", player.getGameProfile().getName(), t);
        }
    }
}
