package com.goida.goidajail.jail;

import com.goida.goidajail.Config;
import com.goida.goidajail.GoidaJail;
import com.goida.goidajail.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Core arrest / release / sentence-tick logic. All methods are server-thread only.
 */
public final class JailManager {

    private JailManager() {}

    // ---- Queries --------------------------------------------------------------------------

    public static boolean isJailed(ServerPlayer p) {
        return p.hasData(ModAttachments.PRISONER.get())
                && p.getData(ModAttachments.PRISONER.get()).isJailed();
    }

    // ---- Arrest ---------------------------------------------------------------------------

    /**
     * Jails a player.
     *
     * @param overrideMinutes explicit sentence length, or {@code null} to use the escalating
     *                        offense-based formula.
     * @param countOffense    whether this arrest records a new offense for future escalation.
     * @return true if the player was jailed, false if already jailed or the jail dimension
     *         could not be reached.
     */
    public static boolean arrest(ServerPlayer target, @Nullable Integer overrideMinutes, boolean countOffense) {
        PrisonerData data = target.getData(ModAttachments.PRISONER.get());
        if (data.isJailed()) {
            return false;
        }
        MinecraftServer server = target.server;
        JailSavedData saved = JailSavedData.get(server);
        UUID id = target.getUUID();
        long now = System.currentTimeMillis();

        int count = countOffense ? saved.registerOffense(id, now) : saved.currentOffenseCount(id, now);
        long sentenceMillis = (overrideMinutes != null)
                ? Math.max(1, overrideMinutes) * 60_000L
                : Config.sentenceMillisFor(Math.max(1, count));

        // Snapshot spawn + game mode BEFORE we change them.
        data.captureSpawn(target);
        data.setPreviousGameModeId(target.gameMode.getGameModeForPlayer().getId());

        // Confiscate inventory into level storage (accessible online + offline for the GUI).
        // Order: read → persist → FLUSH to disk → only then clear the live slots, so a crash
        // can never leave the player with cleared slots and no saved backup.
        ConfiscatedInventory conf = ConfiscatedInventory.captureNoClear(target);
        saved.putConfiscated(id, conf);
        server.overworld().getDataStorage().save();
        ConfiscatedInventory.clearLive(target);

        data.setJailed(true);
        data.setTotalMillis(sentenceMillis);
        data.setRemainingMillis(sentenceMillis);
        data.setLastTickEpoch(now);
        data.setLastRadioEpoch(0L);
        target.setData(ModAttachments.PRISONER.get(), data);

        saved.updatePrisoner(id, target.getGameProfile().getName(), sentenceMillis, sentenceMillis);

        applyConstraints(target);
        // Redirect any respawn to the jail even before our respawn handler runs.
        Vec3 jailSpawn = JailDimension.getSpawn(server);
        target.setRespawnPosition(JailDimension.JAIL_LEVEL, BlockPos.containing(jailSpawn), 0f, true, false);

        if (!JailDimension.sendToJail(target)) {
            // Could not reach the jail dimension: roll everything back so nothing is lost.
            GoidaJail.LOGGER.error("Rolling back arrest of {} (jail dimension unavailable).",
                    target.getGameProfile().getName());
            conf.restore(target);
            saved.removeConfiscated(id);
            data.applySpawn(target);
            restoreGameMode(target, data);
            data.setJailed(false);
            data.setRemainingMillis(0);
            target.setData(ModAttachments.PRISONER.get(), data);
            saved.removePrisoner(id);
            if (countOffense) saved.refundOffense(id);
            return false;
        }

        // Refresh the client's command tree so /goidajail immediately disappears for them.
        target.server.getCommands().sendCommands(target);

        // Полный мут (чат + голос) на время отсидки — если установлен GoidaChat (иначе no-op).
        JailChatMute.apply(target);

        sendArrestMessage(target, sentenceMillis);
        return true;
    }

    // ---- Release --------------------------------------------------------------------------

    public static void release(ServerPlayer p, boolean restoreInventory, boolean refundOffense,
                               @Nullable Component message) {
        MinecraftServer server = p.server;
        JailSavedData saved = JailSavedData.get(server);
        UUID id = p.getUUID();
        PrisonerData data = p.getData(ModAttachments.PRISONER.get());

        // Restore spawn + game mode first.
        data.applySpawn(p);
        restoreGameMode(p, data);

        if (restoreInventory) {
            ConfiscatedInventory conf = saved.getConfiscated(id);
            if (conf != null) {
                conf.restore(p);
            }
        }
        saved.removeConfiscated(id);

        data.setJailed(false);
        data.setRemainingMillis(0);
        p.setData(ModAttachments.PRISONER.get(), data);

        saved.removePrisoner(id);
        saved.clearPendingRelease(id);
        if (refundOffense) saved.refundOffense(id);

        // Снять тюремный мут (только наш) — если установлен GoidaChat (иначе no-op).
        JailChatMute.lift(id);

        // Always release into the OVERWORLD at the configured release point, no matter which
        // dimension the player was jailed from — otherwise a player arrested in e.g. the Nether
        // would reappear at the release coordinates inside that dimension.
        ServerLevel overworld = server.overworld();
        JailDimension.sendToRelease(p, overworld,
                Config.RELEASE_X.get() + 0.5, Config.RELEASE_Y.get(), Config.RELEASE_Z.get() + 0.5);

        // Refresh the client's command tree so /goidajail becomes available again.
        server.getCommands().sendCommands(p);

        if (message != null) {
            p.sendSystemMessage(message);
        }

        // Deliver "item was confiscated" notifications now that the player is leaving jail.
        List<String> notes = saved.takePendingNotifications(id);
        for (String note : notes) {
            p.sendSystemMessage(Component.literal(note));
        }
    }

    /** Queue an offline player for release+restore on their next login. */
    public static void requestOfflineRelease(MinecraftServer server, UUID id) {
        JailSavedData.get(server).addPendingRelease(id);
        // Игрок освобождается — снимаем тюремный мут сразу (работает и для оффлайн), чтобы он не
        // висел до следующего входа. Повторный lift в release() при входе будет no-op.
        JailChatMute.lift(id);
    }

    // ---- Per-tick sentence accounting -----------------------------------------------------

    public static void tickPrisoner(ServerPlayer p) {
        PrisonerData data = p.getData(ModAttachments.PRISONER.get());
        if (!data.isJailed()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = data.getLastTickEpoch();
        if (last <= 0L) last = now;
        long elapsed = now - last;
        if (elapsed < 0L) elapsed = 0L;             // clock moved backwards
        if (elapsed > 5_000L) elapsed = 5_000L;     // clamp huge jumps (clock change / lag spike)
        data.setLastTickEpoch(now);
        data.addRemainingMillis(-elapsed);

        // Keep the player constrained.
        if (p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
            p.setGameMode(GameType.ADVENTURE);
        }
        p.getFoodData().setFoodLevel(20);

        // Dimension / position guard.
        if (!JailDimension.isJailLevel(p.level().dimension())
                || !JailDimension.getInterior(p.server).contains(p.getX(), p.getY(), p.getZ())) {
            JailDimension.recenter(p);
        }

        // "Radio" reminder on the action bar.
        if (now - data.getLastRadioEpoch() >= Config.radioIntervalMillis()) {
            data.setLastRadioEpoch(now);
            p.displayClientMessage(Component.literal(
                    "§c⚠ Вы нарушили правила. Осталось: §e" + formatDuration(data.getRemainingMillis())), true);
        }

        if (data.getRemainingMillis() <= 0L) {
            release(p, true, false, Component.literal("§aВы отбыли наказание и были освобождены."));
        }
    }

    // ---- Login / logout -------------------------------------------------------------------

    public static void handleLogin(ServerPlayer p) {
        MinecraftServer server = p.server;
        JailSavedData saved = JailSavedData.get(server);
        UUID id = p.getUUID();
        PrisonerData data = p.getData(ModAttachments.PRISONER.get());

        if (saved.isPendingRelease(id)) {
            saved.clearPendingRelease(id);
            if (data.isJailed()) {
                release(p, true, false, Component.literal("§aВы были освобождены администратором."));
                return;
            }
        }

        // Reconcile a stale confiscation entry from a crashed arrest: the player is not jailed
        // (their .dat reverted) but a confiscation record persisted → discard it (they kept
        // their items). Prevents duplication.
        if (!data.isJailed() && saved.hasConfiscated(id)) {
            saved.removeConfiscated(id);
            saved.removePrisoner(id);
            saved.clearPendingNotifications(id);
        }

        if (!data.isJailed()) {
            return;
        }
        // Resume the sentence: reset the tick clock so the offline period does not count.
        data.setLastTickEpoch(System.currentTimeMillis());
        data.setLastRadioEpoch(0L);
        p.setData(ModAttachments.PRISONER.get(), data);
        applyConstraints(p);
        // Переутвердить мут: покрывает рестарт сервера, истёкший/снятый мут и случай, когда
        // GoidaChat установили уже после ареста. Идемпотентно; без GoidaChat — no-op.
        JailChatMute.apply(p);

        if (!JailDimension.isJailLevel(p.level().dimension())) {
            JailDimension.sendToJail(p);
        }
    }

    public static void handleLogout(ServerPlayer p) {
        PrisonerData data = p.getData(ModAttachments.PRISONER.get());
        if (!data.isJailed()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = data.getLastTickEpoch();
        if (last > 0L) {
            long elapsed = Math.min(Math.max(0L, now - last), 5_000L);
            data.addRemainingMillis(-elapsed);
        }
        data.setLastTickEpoch(0L);
        p.setData(ModAttachments.PRISONER.get(), data);
        JailSavedData.get(p.server).updatePrisoner(p.getUUID(),
                p.getGameProfile().getName(), data.getRemainingMillis(), data.getTotalMillis());
    }

    // ---- Helpers --------------------------------------------------------------------------

    public static void applyConstraints(ServerPlayer p) {
        p.setGameMode(GameType.ADVENTURE);
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.setRemainingFireTicks(0);
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        p.fallDistance = 0f;
    }

    public static void restoreGameMode(ServerPlayer p, PrisonerData data) {
        p.setGameMode(GameType.byId(data.getPreviousGameModeId()));
    }

    private static void sendArrestMessage(ServerPlayer p, long sentenceMillis) {
        p.sendSystemMessage(Component.literal("§c§lВы помещены в тюрьму за нарушение правил сервера."));
        p.sendSystemMessage(Component.literal("§fСрок наказания: §e" + formatDuration(sentenceMillis)));
        p.sendSystemMessage(Component.literal("§7Время идёт только пока вы находитесь на сервере."));
    }

    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes > 0) {
            return minutes + "м " + seconds + "с";
        }
        return seconds + "с";
    }
}
