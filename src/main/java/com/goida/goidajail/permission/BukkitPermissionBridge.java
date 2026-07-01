package com.goida.goidajail.permission;

import com.goida.goidajail.GoidaJail;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * Bridge to the Bukkit permission system for hybrid cores (Mohist, Arclight, Banner, …).
 *
 * <p>NeoForge's {@code PermissionAPI} and Bukkit's permission system are independent. When
 * LuckPerms (or any permission plugin) is installed as a <b>Bukkit plugin</b> instead of a
 * NeoForge mod, grants live only in Bukkit and never reach {@code PermissionAPI}. On a hybrid
 * core the {@code ServerPlayer} carries a {@code getBukkitEntity()} method (injected by
 * CraftBukkit), so we can reflectively ask the Bukkit player {@code hasPermission(node)}.
 *
 * <p>On a normal NeoForge server the Bukkit classes are absent, so this bridge stays inert.
 */
public final class BukkitPermissionBridge {

    private static final boolean AVAILABLE;
    private static volatile Method getBukkitEntity; // ServerPlayer#getBukkitEntity()
    private static volatile Method hasPermission;   // Permissible#hasPermission(String)

    static {
        boolean ok = false;
        try {
            // Present only on Bukkit-based / hybrid cores.
            Class.forName("org.bukkit.Bukkit");
            ok = true;
            GoidaJail.LOGGER.info("Hybrid core detected — Bukkit permission bridge enabled (node 'goidajail.use').");
        } catch (Throwable ignored) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private BukkitPermissionBridge() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Returns true only if Bukkit is present AND the player has the given dotted node. */
    public static boolean hasPermission(ServerPlayer player, String node) {
        if (!AVAILABLE) {
            return false;
        }
        try {
            Method toBukkit = getBukkitEntity;
            if (toBukkit == null) {
                toBukkit = player.getClass().getMethod("getBukkitEntity");
                getBukkitEntity = toBukkit;
            }
            Object bukkitPlayer = toBukkit.invoke(player);
            if (bukkitPlayer == null) {
                return false;
            }
            Method has = hasPermission;
            if (has == null) {
                has = bukkitPlayer.getClass().getMethod("hasPermission", String.class);
                hasPermission = has;
            }
            return Boolean.TRUE.equals(has.invoke(bukkitPlayer, node));
        } catch (Throwable t) {
            // Never let a reflection hiccup break a permission check.
            return false;
        }
    }
}
