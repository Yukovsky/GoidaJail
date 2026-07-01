package com.goida.goidajail.permission;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Резолвит право игрока через FTB Ranks, если этот мод установлен.
 *
 * <p>FTB Ranks на NeoForge <b>не</b> регистрирует обработчик {@code PermissionAPI}: он оборачивает
 * предикаты Brigadier-команд (узлы {@code command.*}) и в остальном доступен только через свой
 * {@code FTBRanksAPI}. Поэтому проверку права, не связанную напрямую с запуском команды (удар
 * дубинкой на событии атаки и т.п.), FTB Ranks увидит лишь если спросить его API напрямую. Делаем
 * это рефлексией, чтобы не тянуть мод в compile/runtime-зависимости — как {@code BukkitPermissionBridge}.
 *
 * <p>Возвращает {@link Optional#empty()}, когда FTB Ranks не установлен либо у ранга игрока нет
 * явного значения для узла — тогда вызывающий код откатывается на свою обычную логику (OP-уровень,
 * Bukkit-мост).
 */
public final class FtbRanksPermissions {

    // true = FTB Ranks точно отсутствует (классы не загрузились ни разу) — повторных попыток нет.
    private static volatile boolean absent = false;
    private static volatile Method getPermissionValue; // FTBRanksAPI#getPermissionValue(ServerPlayer, String)
    private static volatile Method isEmpty;             // PermissionValue#isEmpty()
    private static volatile Method asBoolean;           // PermissionValue#asBoolean() -> Optional<Boolean>

    private FtbRanksPermissions() {}

    private static boolean init() {
        if (getPermissionValue != null) return true;
        if (absent) return false;
        try {
            Class<?> api = Class.forName("dev.ftb.mods.ftbranks.api.FTBRanksAPI");
            Class<?> value = Class.forName("dev.ftb.mods.ftbranks.api.PermissionValue");
            Method gpv = api.getMethod("getPermissionValue", ServerPlayer.class, String.class);
            isEmpty = value.getMethod("isEmpty");
            asBoolean = value.getMethod("asBoolean");
            getPermissionValue = gpv; // ставим последним: признак готовности
            return true;
        } catch (Throwable t) {
            // ClassNotFound / NoClassDefFound — мод не установлен. Больше не пробуем.
            absent = true;
            return false;
        }
    }

    /**
     * @return явное разрешение/запрет из ранга игрока в FTB Ranks, либо {@link Optional#empty()},
     *         если FTB Ranks не установлен или узел для ранга не задан.
     */
    @SuppressWarnings("unchecked")
    public static Optional<Boolean> check(ServerPlayer player, String node) {
        if (player == null || !init()) return Optional.empty();
        try {
            Object value = getPermissionValue.invoke(null, player, node);
            if (value == null || Boolean.TRUE.equals(isEmpty.invoke(value))) return Optional.empty();
            return (Optional<Boolean>) asBoolean.invoke(value);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }
}
