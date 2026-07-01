package com.goidacraft.goidachat.api;

import java.util.UUID;

/**
 * Compile-only stub mirroring GoidaChat's public {@code MuteApi} surface.
 *
 * <p>GoidaChat is not published to a Maven repository, so this stub lets {@code JailChatMute}
 * compile without a sibling GoidaChat checkout. It is never packaged into the built jar (see the
 * {@code goidachatStub} source set in {@code build.gradle}) and never runs — every call site is
 * guarded by {@code ModList.isLoaded("goidachat")}, so at runtime either the real GoidaChat mod
 * supplies this class, or the guarded code path never executes.
 */
public final class MuteApi {

    private MuteApi() {}

    public static void mutePermanent(UUID uuid, String playerName, String reason, String mutedBy, boolean voice) {}

    public static void mute(UUID uuid, String playerName, String reason, long expiresAt, String mutedBy, boolean voice) {}

    public static void unmute(UUID uuid) {}

    public static boolean unmuteIfBy(UUID uuid, String mutedBy) {
        return false;
    }

    public static boolean isMutedBy(UUID uuid, String mutedBy) {
        return false;
    }
}
