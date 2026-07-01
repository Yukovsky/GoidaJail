package com.goida.goidajail.jail;

import com.goida.goidajail.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the "jail baton" item by the id configured in {@code batonItemId}.
 *
 * <p>GoidaJail does NOT register the item itself — it stays a server-only mod. The baton is
 * registered by KubeJS (which is present on both client and server), so the item exists on
 * clients and they are never kicked, while this mod only looks it up by resource location at
 * runtime. Because KubeJS creates the item without any recipe, it is uncraftable by nature.
 */
public final class Baton {

    private Baton() {}

    /** The configured baton item, or {@code null} if the id is invalid / not registered yet. */
    @Nullable
    public static Item item() {
        ResourceLocation rl = ResourceLocation.tryParse(Config.BATON_ITEM_ID.get());
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(rl);
    }

    /** True if the stack is the configured baton item. */
    public static boolean is(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation rl = ResourceLocation.tryParse(Config.BATON_ITEM_ID.get());
        return rl != null && rl.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
