package com.goida.goidajail.jail;

import com.goida.goidajail.GoidaJail;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Per-player jail state, stored as a data attachment. The sentence is tracked as
 * {@code remainingMillis} (wall-clock), counted down only while the player is online, so it
 * survives relogs and crashes without ever counting offline time.
 *
 * <p>All deserialization is defensive: a corrupted tag can never throw, it merely yields a
 * "not jailed" default that an operator can inspect with {@code /goidajail info}.
 */
public final class PrisonerData implements INBTSerializable<CompoundTag> {

    private boolean jailed = false;
    private long remainingMillis = 0L;
    private long totalMillis = 0L;

    // Original game mode to restore on release (vanilla GameType id; 0 = survival).
    private int previousGameModeId = 0;

    // Original spawn point to restore on release.
    private boolean hadSpawn = false;
    private String spawnDimId = "minecraft:overworld";
    private int spawnX = 0, spawnY = 0, spawnZ = 0;
    private float spawnAngle = 0f;
    private boolean spawnForced = false;

    // Transient runtime helpers (never serialized).
    private transient long lastTickEpoch = 0L;
    private transient long lastRadioEpoch = 0L;

    public PrisonerData() {}

    // ---- State ----------------------------------------------------------------------------

    public boolean isJailed() { return jailed; }
    public void setJailed(boolean v) { this.jailed = v; }

    public long getRemainingMillis() { return remainingMillis; }
    public void setRemainingMillis(long v) { this.remainingMillis = Math.max(0L, v); }
    public void addRemainingMillis(long delta) { this.remainingMillis = Math.max(0L, this.remainingMillis + delta); }

    public long getTotalMillis() { return totalMillis; }
    public void setTotalMillis(long v) { this.totalMillis = Math.max(0L, v); }

    public int getPreviousGameModeId() { return previousGameModeId; }
    public void setPreviousGameModeId(int v) { this.previousGameModeId = v; }

    public long getLastTickEpoch() { return lastTickEpoch; }
    public void setLastTickEpoch(long v) { this.lastTickEpoch = v; }

    public long getLastRadioEpoch() { return lastRadioEpoch; }
    public void setLastRadioEpoch(long v) { this.lastRadioEpoch = v; }

    // ---- Spawn capture / restore ----------------------------------------------------------

    public void captureSpawn(ServerPlayer player) {
        BlockPos pos = player.getRespawnPosition();
        if (pos == null) {
            this.hadSpawn = false;
            return;
        }
        this.hadSpawn = true;
        this.spawnDimId = player.getRespawnDimension().location().toString();
        this.spawnX = pos.getX();
        this.spawnY = pos.getY();
        this.spawnZ = pos.getZ();
        this.spawnAngle = player.getRespawnAngle();
        this.spawnForced = player.isRespawnForced();
    }

    public void applySpawn(ServerPlayer player) {
        if (!hadSpawn) {
            // Player had no personal spawn before; clear any spawn we set so they use world spawn.
            player.setRespawnPosition(Level.OVERWORLD, null, 0f, false, false);
            return;
        }
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, parse(spawnDimId, "minecraft:overworld"));
        player.setRespawnPosition(dim, new BlockPos(spawnX, spawnY, spawnZ), spawnAngle, spawnForced, false);
    }

    // ---- Serialization --------------------------------------------------------------------

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("jailed", jailed);
        tag.putLong("remaining", remainingMillis);
        tag.putLong("total", totalMillis);
        tag.putInt("prevGameMode", previousGameModeId);
        tag.putBoolean("hadSpawn", hadSpawn);
        tag.putString("spawnDim", spawnDimId);
        tag.putInt("spawnX", spawnX);
        tag.putInt("spawnY", spawnY);
        tag.putInt("spawnZ", spawnZ);
        tag.putFloat("spawnAngle", spawnAngle);
        tag.putBoolean("spawnForced", spawnForced);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        try {
            this.jailed = tag.getBoolean("jailed");
            this.remainingMillis = Math.max(0L, tag.getLong("remaining"));
            this.totalMillis = Math.max(0L, tag.getLong("total"));
            this.previousGameModeId = tag.getInt("prevGameMode");
            this.hadSpawn = tag.getBoolean("hadSpawn");
            this.spawnDimId = tag.contains("spawnDim") ? tag.getString("spawnDim") : "minecraft:overworld";
            this.spawnX = tag.getInt("spawnX");
            this.spawnY = tag.getInt("spawnY");
            this.spawnZ = tag.getInt("spawnZ");
            this.spawnAngle = tag.getFloat("spawnAngle");
            this.spawnForced = tag.getBoolean("spawnForced");
        } catch (Exception e) {
            GoidaJail.LOGGER.error("Corrupted PrisonerData tag, defaulting to not-jailed: {}", tag, e);
            this.jailed = false;
        }
    }

    private static ResourceLocation parse(String s, String fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(s);
        return rl != null ? rl : ResourceLocation.parse(fallback);
    }
}
