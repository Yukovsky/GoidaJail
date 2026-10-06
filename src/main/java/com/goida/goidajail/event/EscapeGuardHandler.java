package com.goida.goidajail.event;

import com.goida.goidajail.GoidaJail;
import com.goida.goidajail.jail.JailDimension;
import com.goida.goidajail.jail.JailManager;
import com.goida.goidajail.jail.JailSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;
import java.util.Set;

/**
 * Every escape vector is closed here:
 * <ul>
 *     <li>login/logout — resume the sentence, never counting offline time;</li>
 *     <li>respawn (lowest priority, so we get the final word) — redirect back to jail;</li>
 *     <li>incoming damage — cancelled, so a prisoner cannot die (no Gravestone grave is ever
 *     created in the jail) and cannot PvP;</li>
 *     <li>dimension travel — cancelled unless the mod itself is moving the player;</li>
 *     <li>blocked teleport/storage commands;</li>
 *     <li>the per-tick sentence counter and the position guard.</li>
 * </ul>
 */
@EventBusSubscriber(modid = GoidaJail.MOD_ID)
public final class EscapeGuardHandler {

    /** Commands a jailed player may not run (namespace prefix is stripped before matching). */
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "tp", "teleport", "tpa", "tpaccept", "tpahere", "tphere", "tpask",
            "home", "sethome", "spawn", "back", "warp", "warps", "rtp", "wild",
            "enderchest", "ec", "echest", "wstp", "waystones", "suicide", "kill"
    );

    private EscapeGuardHandler() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        net.minecraft.server.MinecraftServer server = event.getServer();
        ServerLevel jail = JailDimension.level(server);
        if (jail == null) {
            GoidaJail.LOGGER.error("Jail dimension '{}' did not load at server start. "
                    + "Check that the datapack JSON is present.", JailDimension.JAIL_LEVEL.location());
            return;
        }
        JailSavedData saved = JailSavedData.get(server);
        if (!saved.isBoxBuilt()) {
            JailDimension.buildBox(jail, saved);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            JailManager.handleLogin(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            JailManager.handleLogout(p);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        if (!JailManager.isJailed(p)) {
            return;
        }
        JailManager.applyConstraints(p);
        JailDimension.sendToJail(p);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim && JailManager.isJailed(victim)) {
            event.setCanceled(true);
            return;
        }
        Entity source = event.getSource().getEntity();
        if (source instanceof ServerPlayer attacker && JailManager.isJailed(attacker)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDimensionTravel(EntityTravelToDimensionEvent event) {
        if (JailDimension.isBypassing()) {
            return; // the mod itself is moving the player
        }
        if (!(event.getEntity() instanceof ServerPlayer p) || !JailManager.isJailed(p)) {
            return;
        }
        if (!JailDimension.isJailLevel(event.getDimension())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayer p) || !JailManager.isJailed(p)) {
            return;
        }
        String head = firstLiteral(event.getParseResults().getReader().getString());
        if (BLOCKED_COMMANDS.contains(head)) {
            event.setCanceled(true);
            p.sendSystemMessage(Component.literal("§cЭта команда недоступна, пока вы в тюрьме."));
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer p && JailManager.isJailed(p)) {
            p.closeContainer();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        net.minecraft.server.MinecraftServer server = event.getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (JailManager.isJailed(p)) {
                JailManager.tickPrisoner(p);
            }
        }
        if (server.getTickCount() % 10 == 0) {
            JailDimension.tickSelections(server);
        }
    }

    private static String firstLiteral(String input) {
        String s = input.startsWith("/") ? input.substring(1) : input;
        int space = s.indexOf(' ');
        String head = (space >= 0 ? s.substring(0, space) : s).toLowerCase(Locale.ROOT);
        int colon = head.indexOf(':');
        if (colon >= 0) {
            head = head.substring(colon + 1);
        }
        return head;
    }
}
