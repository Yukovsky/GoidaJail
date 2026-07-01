package com.goida.goidajail.event;

import com.goida.goidajail.GoidaJail;
import com.goida.goidajail.jail.Baton;
import com.goida.goidajail.jail.JailManager;
import com.goida.goidajail.permission.JailPermissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * Detects "jail baton" hits. The baton is the item configured by {@code batonItemId} (registered
 * by KubeJS, not by this server-only mod). Hitting another player with it while holding the
 * {@code goidajail.use} permission sends that player to jail.
 */
@EventBusSubscriber(modid = GoidaJail.MOD_ID)
public final class ArrestHandler {

    private ArrestHandler() {}

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        // Attacker must be a server player.
        if (!(event.getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        // Target must be a player — mobs and other entities are ignored entirely.
        if (!(event.getTarget() instanceof ServerPlayer target)) {
            return;
        }
        if (!Baton.is(attacker.getMainHandItem())) {
            return;
        }

        // The baton never deals real damage — it is inert in anyone's hands.
        event.setCanceled(true);

        // In the wrong hands the baton does nothing: without the goidajail.use permission the
        // hit is simply swallowed (no damage, no jailing) and the holder is told it is inert.
        if (!JailPermissions.canUse(attacker)) {
            attacker.sendSystemMessage(Component.literal("§cЭтот предмет вам не подчиняется."));
            return;
        }
        if (target == attacker) {
            return;
        }
        if (JailManager.isJailed(target)) {
            attacker.sendSystemMessage(Component.literal(
                    "§e" + target.getGameProfile().getName() + " уже находится в тюрьме."));
            return;
        }

        if (JailManager.arrest(target, null, true)) {
            attacker.sendSystemMessage(Component.literal(
                    "§a" + target.getGameProfile().getName() + " отправлен в тюрьму."));
        } else {
            attacker.sendSystemMessage(Component.literal(
                    "§cНе удалось отправить игрока в тюрьму (см. лог сервера)."));
        }
    }
}
