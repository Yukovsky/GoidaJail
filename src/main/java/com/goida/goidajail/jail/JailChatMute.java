package com.goida.goidajail.jail;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * Необязательная интеграция с GoidaChat: заключённый получает полный мут (чат + голос) на всё
 * время, пока он в тюрьме.
 *
 * <p>Всё изолировано за {@code ModList.isLoaded("goidachat")}, а единственный класс GoidaChat
 * ({@code MuteApi}) затрагивается строго ПОСЛЕ этой проверки. Поэтому без GoidaChat на сервере
 * GoidaJail грузится и работает как обычно — просто без мута, без {@code NoClassDefFoundError}.
 *
 * <p>Мут <b>вечный</b> (до снятия): срок в тюрьме не идёт, пока игрок оффлайн, поэтому фиксированная
 * по стенным часам длительность не подходит. Вместо этого мут утверждается при аресте и при входе,
 * а снимается при освобождении — и точно повторяет состояние «в тюрьме».
 */
public final class JailChatMute {

    /** Тег источника мута — по нему мы снимаем ТОЛЬКО свой мут, не трогая чужой (напр. админский). */
    private static final String SOURCE = "GoidaJail";
    private static final String REASON = "Заключение в тюрьме";

    private static Boolean chatLoaded;

    private JailChatMute() {}

    private static boolean chatLoaded() {
        Boolean l = chatLoaded;
        if (l == null) {
            l = ModList.get().isLoaded("goidachat");
            chatLoaded = l;
        }
        return l;
    }

    /** Наложить/подтвердить полный мут заключённого. Идемпотентно. Без GoidaChat — no-op. */
    public static void apply(ServerPlayer player) {
        if (!chatLoaded() || player == null) return;
        com.goidacraft.goidachat.api.MuteApi.mutePermanent(
                player.getUUID(), player.getGameProfile().getName(), REASON, SOURCE, true);
    }

    /** Снять тюремный мут (только если он наш). Работает и для оффлайн-игрока. Без GoidaChat — no-op. */
    public static void lift(UUID playerId) {
        if (!chatLoaded() || playerId == null) return;
        com.goidacraft.goidachat.api.MuteApi.unmuteIfBy(playerId, SOURCE);
    }
}
