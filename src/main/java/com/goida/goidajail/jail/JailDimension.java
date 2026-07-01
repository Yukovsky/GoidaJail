package com.goida.goidajail.jail;

import com.goida.goidajail.GoidaJail;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geometry and teleportation for the data-driven {@code goidajail:jail} dimension.
 *
 * <p>A single hollow bedrock box at the origin houses all prisoners. PvP is disabled and the
 * box is closed defense-in-depth, but the real escape protection is behavioural (adventure
 * mode, dimension-travel cancel, respawn redirect, position guard) since the player has no
 * items in jail.
 */
public final class JailDimension {

    public static final ResourceKey<Level> JAIL_LEVEL = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(GoidaJail.MOD_ID, "jail"));

    /**
     * Set while the mod itself teleports a prisoner, so the escape guards know to let that
     * particular dimension change through.
     */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Per-admin region being edited (corners chosen but not yet applied). Highlighted live. */
    private static final Map<UUID, PendingRegion> pending = new ConcurrentHashMap<>();

    /**
     * Per-admin location saved right before {@code /goidajail visit}, so {@code /goidajail back}
     * can return them to exactly where (and which dimension) they came from. In-memory only —
     * it is a convenience buffer, not persisted across restarts.
     */
    private static final Map<UUID, ReturnPoint> returns = new ConcurrentHashMap<>();

    /** A full position + dimension + facing snapshot used to return an admin after a visit. */
    public record ReturnPoint(ResourceKey<Level> dim, double x, double y, double z,
                              float yaw, float pitch) {}

    private JailDimension() {}

    public static boolean isBypassing() {
        return BYPASS.get();
    }

    @Nullable
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(JAIL_LEVEL);
    }

    public static boolean isJailLevel(@Nullable ResourceKey<Level> dim) {
        return JAIL_LEVEL.equals(dim);
    }

    // ---- Dynamic geometry -----------------------------------------------------------------

    /** Returns the spawn point for jailed players, read from persistent config. */
    public static Vec3 getSpawn(MinecraftServer server) {
        JailSavedData s = JailSavedData.get(server);
        return new Vec3(s.getJailSpawnX(), s.getJailSpawnY(), s.getJailSpawnZ());
    }

    /** Returns the AABB used by the position guard — exact boundary set by the admin. */
    public static AABB getInterior(MinecraftServer server) {
        JailSavedData s = JailSavedData.get(server);
        return new AABB(s.getBoxX1(), s.getBoxY1(), s.getBoxZ1(),
                        s.getBoxX2() + 1, s.getBoxY2() + 1, s.getBoxZ2() + 1);
    }

    // ---- Teleportation --------------------------------------------------------------------

    /** Builds the box if needed and teleports the player onto the jail platform. */
    public static boolean sendToJail(ServerPlayer player) {
        ServerLevel jail = level(player.server);
        if (jail == null) {
            GoidaJail.LOGGER.error("Jail dimension '{}' is not loaded; cannot jail {}",
                    JAIL_LEVEL.location(), player.getGameProfile().getName());
            return false;
        }
        Vec3 spawn = getSpawn(player.server);
        teleport(player, jail, spawn.x, spawn.y, spawn.z, player.getYRot(), 0f);
        return true;
    }

    /** Teleports the player to the configured overworld release point. */
    public static void sendToRelease(ServerPlayer player, ServerLevel overworld, double x, double y, double z) {
        teleport(player, overworld, x, y, z, player.getYRot(), 0f);
    }

    /** Re-centres a prisoner who somehow left the box (position guard). */
    public static void recenter(ServerPlayer player) {
        ServerLevel jail = level(player.server);
        if (jail == null) return;
        Vec3 spawn = getSpawn(player.server);
        teleport(player, jail, spawn.x, spawn.y, spawn.z, player.getYRot(), 0f);
    }

    /**
     * Teleports an admin into the jail without jailing them.
     * Destination is controlled by visitX/Y/Z in the server config.
     * Non-jailed players are not subject to dimension-travel cancel, so no bypass needed.
     */
    public static boolean adminVisit(ServerPlayer player) {
        ServerLevel jail = level(player.server);
        if (jail == null) return false;
        // Remember where the admin was so /goidajail back can return them. Skip if they are
        // already in the jail dimension, so visiting twice keeps the original outside location.
        if (!isJailLevel(player.level().dimension())) {
            saveReturn(player);
        }
        double x = com.goida.goidajail.Config.VISIT_X.get() + 0.5;
        double y = com.goida.goidajail.Config.VISIT_Y.get();
        double z = com.goida.goidajail.Config.VISIT_Z.get() + 0.5;
        player.teleportTo(jail, x, y, z, player.getYRot(), 0f);
        return true;
    }

    /** Captures the player's current position, dimension and facing as their return point. */
    public static void saveReturn(ServerPlayer player) {
        returns.put(player.getUUID(), new ReturnPoint(
                player.level().dimension(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));
    }

    /**
     * Teleports the admin back to the location saved by their last {@link #adminVisit}, then
     * clears it.
     *
     * @return {@code 1} on success, {@code 0} if no return point is saved, {@code -1} if the
     *         saved dimension is no longer loaded.
     */
    public static int adminReturn(ServerPlayer player) {
        ReturnPoint rp = returns.get(player.getUUID());
        if (rp == null) return 0;
        ServerLevel target = player.server.getLevel(rp.dim());
        if (target == null) return -1;
        player.teleportTo(target, rp.x(), rp.y(), rp.z(), rp.yaw(), rp.pitch());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0f;
        returns.remove(player.getUUID());
        return 1;
    }

    private static void teleport(ServerPlayer player, ServerLevel target,
                                 double x, double y, double z, float yaw, float pitch) {
        BYPASS.set(Boolean.TRUE);
        try {
            player.teleportTo(target, x, y, z, yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0f;
        } finally {
            BYPASS.set(Boolean.FALSE);
        }
    }

    // ---- First-time box construction ------------------------------------------------------

    /**
     * Builds the bedrock box exactly once for a fresh world.
     * Must only be called when {@link JailSavedData#isBoxBuilt()} returns {@code false}.
     */
    public static void buildBox(ServerLevel jail, JailSavedData saved) {
        int x1 = saved.getBoxX1(), y1 = saved.getBoxY1(), z1 = saved.getBoxZ1();
        int x2 = saved.getBoxX2(), y2 = saved.getBoxY2(), z2 = saved.getBoxZ2();
        final int wall = 3;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    boolean shell = x < x1 + wall || x > x2 - wall
                            || z < z1 + wall || z > z2 - wall
                            || y == y1 || y == y2;
                    pos.set(x, y, z);
                    jail.setBlock(pos, shell
                            ? Blocks.BEDROCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        saved.markBoxBuilt();
        GoidaJail.LOGGER.info("GoidaJail: built initial bedrock box [{},{},{}] → [{},{},{}].",
                x1, y1, z1, x2, y2, z2);
    }

    // ---- Pending region selection (live particle highlight) -------------------------------

    /**
     * A region an admin is selecting: corner 1 and/or corner 2, held in memory until the admin
     * applies it (commits to {@link JailSavedData}) or clears it. While it exists, the owner sees
     * it highlighted with particles every few ticks. Not persisted — it is only an editing buffer.
     */
    public static final class PendingRegion {
        public Integer x1, y1, z1; // corner 1, null until setpos1
        public Integer x2, y2, z2; // corner 2, null until setpos2
        public boolean hasP1() { return x1 != null; }
        public boolean hasP2() { return x2 != null; }
        public boolean complete() { return hasP1() && hasP2(); }
    }

    /** Returns the admin's pending selection, creating an empty one on first use. */
    public static PendingRegion editing(UUID admin) {
        return pending.computeIfAbsent(admin, k -> new PendingRegion());
    }

    @Nullable
    public static PendingRegion pendingOf(UUID admin) {
        return pending.get(admin);
    }

    public static void clearPending(UUID admin) {
        pending.remove(admin);
    }

    /** Called every 10 ticks from {@code EscapeGuardHandler}: highlights each admin's selection. */
    public static void tickSelections(MinecraftServer server) {
        if (pending.isEmpty()) return;
        for (var entry : pending.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            if (p == null || !isJailLevel(p.level().dimension())) continue;
            if (!(p.level() instanceof ServerLevel level)) continue;
            PendingRegion r = entry.getValue();
            if (r.complete()) {
                int x1 = Math.min(r.x1, r.x2), y1 = Math.min(r.y1, r.y2), z1 = Math.min(r.z1, r.z2);
                int x2 = Math.max(r.x1, r.x2), y2 = Math.max(r.y1, r.y2), z2 = Math.max(r.z1, r.z2);
                drawBox(p, level, x1, y1, z1, x2, y2, z2);
            } else if (r.hasP1()) {
                drawMarker(p, level, r.x1, r.y1, r.z1);
            } else if (r.hasP2()) {
                drawMarker(p, level, r.x2, r.y2, r.z2);
            }
        }
    }

    /** END_ROD particles along the 12 edges of the box (only this admin sees them). */
    private static void drawBox(ServerPlayer player, ServerLevel level,
                                int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            dot(player, level, ParticleTypes.END_ROD, x + 0.5, y1, z1 + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x + 0.5, y1, z2 + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x + 0.5, y2, z1 + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x + 0.5, y2, z2 + 0.5);
        }
        for (int y = y1 + 1; y < y2; y++) {
            dot(player, level, ParticleTypes.END_ROD, x1 + 0.5, y, z1 + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x1 + 0.5, y, z2 + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x2 + 0.5, y, z1 + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x2 + 0.5, y, z2 + 0.5);
        }
        for (int z = z1 + 1; z < z2; z++) {
            dot(player, level, ParticleTypes.END_ROD, x1 + 0.5, y1, z + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x1 + 0.5, y2, z + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x2 + 0.5, y1, z + 0.5);
            dot(player, level, ParticleTypes.END_ROD, x2 + 0.5, y2, z + 0.5);
        }
    }

    /** A bright FLAME column marking a single chosen corner (when only one point is set). */
    private static void drawMarker(ServerPlayer player, ServerLevel level, int x, int y, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            dot(player, level, ParticleTypes.FLAME, x + 0.5, y + dy, z + 0.5);
        }
    }

    private static void dot(ServerPlayer player, ServerLevel level,
                            net.minecraft.core.particles.ParticleOptions type,
                            double x, double y, double z) {
        level.sendParticles(player, type, true, x, y, z, 1, 0, 0, 0, 0.0);
    }
}
