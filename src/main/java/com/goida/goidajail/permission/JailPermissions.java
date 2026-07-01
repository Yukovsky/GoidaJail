package com.goida.goidajail.permission;

import com.goida.goidajail.Config;
import com.goida.goidajail.GoidaJail;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Permission gate for the jail baton and commands.
 *
 * <p>The right is exposed as the NeoForge permission node {@code goidajail.use}. With no
 * permission mod installed the node falls back to the vanilla op-level check
 * ({@code batonPermissionLevel}, default 2). With a permission mod such as LuckPerms installed,
 * an operator can grant the node to ANY player — opped or not — for example:
 * <pre>/lp user &lt;name&gt; permission set goidajail.use true</pre>
 */
@EventBusSubscriber(modid = GoidaJail.MOD_ID)
public final class JailPermissions {

    /** {@code goidajail.use} — may use the baton and the core /goidajail commands. */
    public static final PermissionNode<Boolean> USE = node("use");
    /** {@code goidajail.confiscate} — may open a prisoner's confiscation inventory. */
    public static final PermissionNode<Boolean> CONFISCATE = node("confiscate");
    /** {@code goidajail.confiscate.silent} — may confiscate without notifying the prisoner. */
    public static final PermissionNode<Boolean> SILENT = node("confiscate.silent");
    /** {@code goidajail.confiscate.log} — may view the confiscation audit log. */
    public static final PermissionNode<Boolean> LOG = node("confiscate.log");

    /** Dotted forms used by Bukkit permission plugins on hybrid cores. */
    public static final String BUKKIT_NODE = "goidajail.use";
    public static final String BUKKIT_CONFISCATE = "goidajail.confiscate";
    public static final String BUKKIT_SILENT = "goidajail.confiscate.silent";
    public static final String BUKKIT_LOG = "goidajail.confiscate.log";

    private JailPermissions() {}

    private static PermissionNode<Boolean> node(String name) {
        return new PermissionNode<>(GoidaJail.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, playerUUID, context) ->
                        player != null && player.hasPermissions(Config.BATON_PERMISSION_LEVEL.get()));
    }

    @SubscribeEvent
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(USE, CONFISCATE, SILENT, LOG);
    }

    /**
     * True if this online player may use the baton / commands. Honours, in order:
     * NeoForge {@code PermissionAPI} (covers LuckPerms-as-a-mod and the op-level fallback), then
     * FTB Ranks (its {@code goidajail.*} rank nodes), then the Bukkit permission system on hybrid
     * cores (covers LuckPerms-as-a-plugin).
     */
    public static boolean canUse(ServerPlayer player) {
        return has(player, USE, BUKKIT_NODE);
    }

    public static boolean canConfiscate(ServerPlayer player) {
        return has(player, CONFISCATE, BUKKIT_CONFISCATE);
    }

    public static boolean canSilent(ServerPlayer player) {
        return has(player, SILENT, BUKKIT_SILENT);
    }

    public static boolean canLog(ServerPlayer player) {
        return has(player, LOG, BUKKIT_LOG);
    }

    // ---- Command-source variants (console / command blocks pass via op level) -------------

    public static boolean canUse(CommandSourceStack source) {
        return has(source, p -> canUse(p));
    }

    public static boolean canConfiscate(CommandSourceStack source) {
        return has(source, p -> canConfiscate(p));
    }

    public static boolean canSilent(CommandSourceStack source) {
        return has(source, p -> canSilent(p));
    }

    public static boolean canLog(CommandSourceStack source) {
        return has(source, p -> canLog(p));
    }

    private static boolean has(ServerPlayer player, PermissionNode<Boolean> node, String bukkitNode) {
        // NeoForge PermissionAPI: покрывает LuckPerms-как-мод и OP-fallback внутри резолвера узла.
        if (Boolean.TRUE.equals(PermissionAPI.getPermission(player, node))) {
            return true;
        }
        // FTB Ranks не цепляется к PermissionAPI — спрашиваем его API напрямую по точечному узлу.
        java.util.Optional<Boolean> ftb = FtbRanksPermissions.check(player, bukkitNode);
        if (ftb.isPresent()) {
            return ftb.get();
        }
        // Гибридные ядра: LuckPerms-как-Bukkit-плагин.
        return BukkitPermissionBridge.hasPermission(player, bukkitNode);
    }

    private static boolean has(CommandSourceStack source, java.util.function.Predicate<ServerPlayer> playerCheck) {
        if (source.hasPermission(Config.BATON_PERMISSION_LEVEL.get())) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p && playerCheck.test(p);
    }
}
