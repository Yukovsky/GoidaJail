package com.goida.goidajail.command;

import com.goida.goidajail.Config;
import com.goida.goidajail.GoidaJail;
import com.goida.goidajail.jail.JailDimension;
import com.goida.goidajail.jail.JailManager;
import com.goida.goidajail.jail.JailSavedData;
import com.goida.goidajail.jail.Baton;
import com.goida.goidajail.jail.ConfiscatedInventory;
import com.goida.goidajail.jail.PrisonerData;
import com.goida.goidajail.menu.ConfiscationMenu;
import com.goida.goidajail.permission.JailPermissions;
import com.goida.goidajail.registry.ModAttachments;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /goidajail ...} administration. Every node requires the configured permission level.
 * Includes recovery tooling ({@code restoreinv}, {@code clearstate}) for the rare case of a
 * corrupted backup, and {@code pardon} to undo a mistaken arrest (release + full refund).
 */
@EventBusSubscriber(modid = GoidaJail.MOD_ID)
public final class JailCommands {

    private JailCommands() {}


    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralCommandNode<CommandSourceStack> root = d.register(Commands.literal("goidajail")
                // A prisoner can never use /goidajail at all. Otherwise the command shows up for
                // anyone holding ANY of the independent jail rights; each subcommand then carries
                // its own specific permission below.
                .requires(src -> !isSourceJailed(src)
                        && (JailPermissions.canUse(src) || JailPermissions.canConfiscate(src)
                            || JailPermissions.canSilent(src) || JailPermissions.canLog(src)))
                .executes(JailCommands::help)

                .then(Commands.literal("help")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::help))

                .then(Commands.literal("baton")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::giveBaton))

                .then(Commands.literal("jail")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(ctx -> jail(ctx, null))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(ctx -> jail(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))))

                // Прямой аргумент: /goidajail <игрок> [минуты] (удобный шорткат для /goidajail jail ...)
                .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                        .requires(JailPermissions::canUse)
                        .executes(ctx -> jail(ctx, null))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                .executes(ctx -> jail(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))

                .then(Commands.literal("jailoffline")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> jailOffline(ctx, null))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(ctx -> jailOffline(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))))

                .then(Commands.literal("release")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(ctx -> releaseCmd(ctx, false))))

                .then(Commands.literal("pardon")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(ctx -> releaseCmd(ctx, true))))

                .then(Commands.literal("time")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(ctx -> changeTime(ctx, false)))))

                .then(Commands.literal("addtime")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(ctx -> changeTime(ctx, true)))))

                .then(Commands.literal("info")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(JailCommands::info)))

                .then(Commands.literal("list")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::list))

                .then(Commands.literal("restoreinv")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(JailCommands::restoreInv)))

                .then(Commands.literal("clearstate")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(JailCommands::clearState)))

                .then(Commands.literal("clearoffenses")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(JailCommands::clearOffenses)))

                .then(Commands.literal("releaseoffline")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(JailCommands::releaseOffline)))

                // ---- Confiscation feature (independent permissions) ----
                .then(Commands.literal("confiscate")
                        .requires(JailPermissions::canConfiscate)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> confiscate(ctx, null))
                                .then(Commands.literal("notify")
                                        .requires(JailPermissions::canSilent)
                                        .executes(ctx -> confiscate(ctx, Boolean.FALSE)))
                                .then(Commands.literal("silent")
                                        .requires(JailPermissions::canSilent)
                                        .executes(ctx -> confiscate(ctx, Boolean.TRUE)))))

                .then(Commands.literal("confiscatesilent")
                        .requires(JailPermissions::canSilent)
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(JailCommands::setSilentDefault)))

                .then(Commands.literal("confiscationlog")
                        .requires(JailPermissions::canLog)
                        .executes(ctx -> confiscationLog(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> confiscationLog(ctx, IntegerArgumentType.getInteger(ctx, "page")))))

                // ---- Jail configuration (region + spawn) ----
                .then(Commands.literal("visit")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::visitJail))

                .then(Commands.literal("back")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::backFromVisit))

                .then(Commands.literal("setpos1")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> setPos(ctx, 1))))

                .then(Commands.literal("setpos2")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> setPos(ctx, 2))))

                .then(Commands.literal("applyregion")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::applyRegion))

                .then(Commands.literal("clearregion")
                        .requires(JailPermissions::canUse)
                        .executes(JailCommands::clearRegion))

                .then(Commands.literal("setjailspawn")
                        .requires(JailPermissions::canUse)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(JailCommands::setJailSpawn)))
        );

        // Алиас /jail -> /goidajail (поддерживает /jail, /jail <игрок> [минуты], /jail baton, /jail list и т.д.)
        d.register(Commands.literal("jail")
                .requires(src -> !isSourceJailed(src) && JailPermissions.canUse(src))
                .redirect(root));

        // Алиас /jailoffline <игрок> [минуты] -> запланировать арест оффлайн-игрока
        d.register(Commands.literal("jailoffline")
                .requires(src -> !isSourceJailed(src) && JailPermissions.canUse(src))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> jailOffline(ctx, null))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                .executes(ctx -> jailOffline(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))));

        // Алиас /unjail <игрок> -> освободить заключённого (/goidajail release)
        d.register(Commands.literal("unjail")
                .requires(src -> !isSourceJailed(src) && JailPermissions.canUse(src))
                .executes(ctx -> {
                    ctx.getSource().sendFailure(Component.literal("§cИспользование: /unjail <игрок>"));
                    return 0;
                })
                .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                        .executes(ctx -> releaseCmd(ctx, false))));

        // Алиас /unjailoffline <игрок> -> освободить оффлайн-игрока (/goidajail releaseoffline)
        d.register(Commands.literal("unjailoffline")
                .requires(src -> !isSourceJailed(src) && JailPermissions.canUse(src))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(JailCommands::releaseOffline)));
    }

    // ---- Executors ------------------------------------------------------------------------

    private static int giveBaton(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Item item = Baton.item();
        if (item == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cПредмет дубинки '" + Config.BATON_ITEM_ID.get() + "' не зарегистрирован. "
                    + "Проверьте, что KubeJS-скрипт установлен (см. README)."));
            return 0;
        }
        // Don't hand out a second baton if one is already in the inventory.
        if (hasBaton(player)) {
            ctx.getSource().sendFailure(Component.literal("§cУ вас уже есть дубинка."));
            return 0;
        }
        ItemStack baton = new ItemStack(item);
        if (!player.getInventory().add(baton)) {
            player.drop(baton, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aВыдана дубинка правосудия. Ударьте ею игрока, чтобы посадить."), false);
        return 1;
    }

    private static boolean hasBaton(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (Baton.is(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private static int jail(CommandContext<CommandSourceStack> ctx, Integer minutes) throws CommandSyntaxException {
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "targets");
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        int n = 0;
        for (GameProfile profile : targets) {
            UUID id = profile.getId();
            String pname = profile.getName();
            if (blockSelf(ctx.getSource(), id, "посадить")) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                if (JailManager.isJailed(online)) {
                    ctx.getSource().sendFailure(Component.literal("§e" + pname + " уже в тюрьме."));
                    continue;
                }
                if (JailManager.arrest(online, minutes, true)) {
                    n++;
                    ctx.getSource().sendSuccess(() -> Component.literal("§a" + pname + " отправлен в тюрьму."), true);
                } else {
                    ctx.getSource().sendFailure(Component.literal("§cНе удалось посадить " + pname + "."));
                }
            } else {
                // Offline player
                if (saved.prisoners().containsKey(id)) {
                    ctx.getSource().sendFailure(Component.literal("§e" + pname + " (оффлайн) уже в тюрьме."));
                    continue;
                }
                boolean updated = saved.isPendingArrest(id);
                JailManager.requestOfflineArrest(server, id, pname, minutes, true);
                n++;
                final String timeMsg = (minutes != null) ? " (" + minutes + " мин)" : "";
                if (updated) {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§aАрест для " + pname + " обновлён. Он будет помещён в тюрьму при следующем входе" + timeMsg + "."), true);
                } else {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a" + pname + " сейчас оффлайн. Он будет помещён в тюрьму при следующем входе" + timeMsg + "."), true);
                }
            }
        }
        return n;
    }

    private static int jailOffline(CommandContext<CommandSourceStack> ctx, Integer minutes) {
        String pname = StringArgumentType.getString(ctx, "name");
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);

        ServerPlayer online = server.getPlayerList().getPlayerByName(pname);
        if (online != null) {
            if (blockSelf(ctx.getSource(), online.getUUID(), "посадить")) {
                return 0;
            }
            if (JailManager.isJailed(online)) {
                ctx.getSource().sendFailure(Component.literal("§e" + online.getGameProfile().getName() + " уже в тюрьме."));
                return 0;
            }
            if (JailManager.arrest(online, minutes, true)) {
                ctx.getSource().sendSuccess(() -> Component.literal("§a" + online.getGameProfile().getName() + " отправлен в тюрьму."), true);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.literal("§cНе удалось посадить " + online.getGameProfile().getName() + "."));
                return 0;
            }
        }

        UUID id;
        String resolvedName = pname;
        Optional<GameProfile> profile = server.getProfileCache() == null
                ? Optional.empty() : server.getProfileCache().get(pname);
        if (profile.isPresent()) {
            id = profile.get().getId();
            resolvedName = profile.get().getName();
        } else if (!server.usesAuthentication()) {
            id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + pname).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            ctx.getSource().sendFailure(Component.literal("§cИгрок '" + pname + "' не найден в кэше профилей."));
            return 0;
        }

        if (blockSelf(ctx.getSource(), id, "посадить")) {
            return 0;
        }
        if (saved.prisoners().containsKey(id)) {
            ctx.getSource().sendFailure(Component.literal("§e" + resolvedName + " (оффлайн) уже в тюрьме."));
            return 0;
        }

        boolean updated = saved.isPendingArrest(id);
        JailManager.requestOfflineArrest(server, id, resolvedName, minutes, true);
        final String finalName = resolvedName;
        final String timeMsg = (minutes != null) ? " (" + minutes + " мин)" : "";
        if (updated) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aАрест для " + finalName + " обновлён. Он будет помещён в тюрьму при следующем входе" + timeMsg + "."), true);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§a" + finalName + " сейчас оффлайн. Он будет помещён в тюрьму при следующем входе" + timeMsg + "."), true);
        }
        return 1;
    }

    private static int releaseCmd(CommandContext<CommandSourceStack> ctx, boolean refundOffense) throws CommandSyntaxException {
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "targets");
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        int n = 0;
        for (GameProfile profile : targets) {
            UUID id = profile.getId();
            String pname = profile.getName();
            if (blockSelf(ctx.getSource(), id, "освободить")) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                if (!JailManager.isJailed(online)) {
                    ctx.getSource().sendFailure(Component.literal("§e" + pname + " не в тюрьме."));
                    continue;
                }
                JailManager.release(online, true, refundOffense,
                        Component.literal(refundOffense ? "§aВы были помилованы администратором."
                                                        : "§aВы были освобождены администратором."));
                n++;
                String verb = refundOffense ? "помилован (нарушение снято)" : "освобождён";
                ctx.getSource().sendSuccess(() -> Component.literal("§a" + pname + " " + verb + "."), true);
            } else {
                // Offline player
                boolean handled = false;
                if (saved.isPendingArrest(id)) {
                    saved.clearPendingArrest(id);
                    ctx.getSource().sendSuccess(() -> Component.literal("§aЗапланированный арест для " + pname + " отменён."), true);
                    handled = true;
                    n++;
                }
                if (saved.prisoners().containsKey(id)) {
                    JailManager.requestOfflineRelease(server, id);
                    if (refundOffense) saved.refundOffense(id);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a" + pname + " (оффлайн) будет освобождён и получит вещи при следующем входе."), true);
                    handled = true;
                    n++;
                }
                if (!handled) {
                    ctx.getSource().sendFailure(Component.literal("§e" + pname + " не в тюрьме."));
                }
            }
        }
        return n;
    }

    private static int changeTime(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "targets");
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        long deltaMillis = minutes * 60_000L;
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        int n = 0;
        for (GameProfile profile : targets) {
            UUID id = profile.getId();
            String pname = profile.getName();
            if (blockSelf(ctx.getSource(), id, "менять срок")) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                PrisonerData data = online.getData(ModAttachments.PRISONER.get());
                if (!data.isJailed()) {
                    ctx.getSource().sendFailure(Component.literal("§e" + pname + " не в тюрьме."));
                    continue;
                }
                if (add) {
                    data.addRemainingMillis(deltaMillis);
                    data.setTotalMillis(data.getTotalMillis() + Math.max(0, deltaMillis));
                } else {
                    data.setRemainingMillis(deltaMillis);
                    data.setTotalMillis(Math.max(data.getTotalMillis(), deltaMillis));
                }
                online.setData(ModAttachments.PRISONER.get(), data);
                saved.updatePrisoner(id, pname, data.getRemainingMillis(), data.getTotalMillis());
                n++;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§aСрок для " + pname + ": §e" + JailManager.formatDuration(data.getRemainingMillis())), true);
            } else if (saved.prisoners().containsKey(id)) {
                // Offline prisoner
                JailSavedData.PrisonerSummary s = saved.prisoners().get(id);
                long newRemaining = add ? Math.max(0L, s.remainingMillis + deltaMillis) : deltaMillis;
                long newTotal = add ? s.totalMillis + Math.max(0, deltaMillis) : Math.max(s.totalMillis, deltaMillis);
                saved.updatePrisonerTime(id, newRemaining, newTotal);
                n++;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§aСрок для " + pname + " (оффлайн): §e" + JailManager.formatDuration(newRemaining)), true);
            } else if (saved.isPendingArrest(id)) {
                // Pending arrest
                JailSavedData.PendingArrest pa = saved.getPendingArrest(id);
                int baseMin = pa.overrideMinutes != null ? pa.overrideMinutes : 0;
                int newMin = add ? Math.max(1, baseMin + minutes) : minutes;
                saved.addPendingArrest(id, new JailSavedData.PendingArrest(pname, newMin, pa.countOffense, pa.timestamp));
                n++;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§aЗапланированный срок для " + pname + " (ожидает входа): §e" + newMin + " мин."), true);
            } else {
                ctx.getSource().sendFailure(Component.literal("§e" + pname + " не в тюрьме."));
            }
        }
        return n;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "targets");
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        long now = System.currentTimeMillis();
        for (GameProfile profile : targets) {
            UUID id = profile.getId();
            String pname = profile.getName();
            int offenses = saved.currentOffenseCount(id, now);
            boolean backup = saved.hasConfiscated(id);
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                PrisonerData data = online.getData(ModAttachments.PRISONER.get());
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6" + pname + "§f: " + (data.isJailed()
                                ? "§cв тюрьме (онлайн), осталось §e" + JailManager.formatDuration(data.getRemainingMillis())
                                + "§f из §e" + JailManager.formatDuration(data.getTotalMillis())
                                : "§aна свободе")
                        + " §7| нарушений: §f" + offenses
                        + " §7| изъятый инвентарь: " + (backup ? "§aесть" : "§7нет")), false);
            } else if (saved.isPendingArrest(id)) {
                JailSavedData.PendingArrest pa = saved.getPendingArrest(id);
                String dur = pa.overrideMinutes != null ? (pa.overrideMinutes + " мин") : "по истории нарушений";
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6" + pname + "§f: §eожидает ареста при следующем входе (" + dur + ")"
                        + " §7| нарушений: §f" + offenses
                        + " §7| изъятый инвентарь: " + (backup ? "§aесть" : "§7нет (будет изъят при входе)")), false);
            } else if (saved.prisoners().containsKey(id)) {
                JailSavedData.PrisonerSummary s = saved.prisoners().get(id);
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6" + pname + "§f: §cв тюрьме (оффлайн), осталось §e" + JailManager.formatDuration(s.remainingMillis)
                        + "§f из §e" + JailManager.formatDuration(s.totalMillis)
                        + " §7| нарушений: §f" + offenses
                        + " §7| изъятый инвентарь: " + (backup ? "§aесть" : "§7нет")), false);
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6" + pname + "§f: §aна свободе (оффлайн)"
                        + " §7| нарушений: §f" + offenses
                        + " §7| изъятый инвентарь: " + (backup ? "§aесть" : "§7нет")), false);
            }
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        var prisoners = saved.prisoners();
        var pendingArrests = saved.pendingArrests();
        if (prisoners.isEmpty() && pendingArrests.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7В тюрьме никого нет."), false);
            return 0;
        }
        if (!prisoners.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§6Заключённые (§f" + prisoners.size() + "§6):"), false);
            prisoners.forEach((uuid, summary) -> {
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                long remaining = (online != null)
                        ? online.getData(ModAttachments.PRISONER.get()).getRemainingMillis()
                        : summary.remainingMillis;
                String status = online != null ? "§a●" : "§7○";
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  " + status + " §f" + summary.name + " §7— §e" + JailManager.formatDuration(remaining)), false);
            });
        }
        if (!pendingArrests.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§eОжидают ареста при входе (§f" + pendingArrests.size() + "§e):"), false);
            pendingArrests.forEach((uuid, pa) -> {
                String dur = pa.overrideMinutes != null ? (pa.overrideMinutes + " мин") : "по истории";
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  §c⏳ §f" + pa.name + " §7— §e" + dur), false);
            });
        }
        return prisoners.size() + pendingArrests.size();
    }

    private static int restoreInv(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        JailSavedData saved = JailSavedData.get(ctx.getSource().getServer());
        int n = 0;
        for (ServerPlayer target : targets) {
            if (blockSelf(ctx.getSource(), target, "восстанавливать вещи")) {
                continue;
            }
            ConfiscatedInventory conf = saved.getConfiscated(target.getUUID());
            if (conf == null) {
                ctx.getSource().sendFailure(Component.literal("§cУ " + name(target) + " нет сохранённого инвентаря."));
                continue;
            }
            conf.restore(target);
            saved.removeConfiscated(target.getUUID());
            n++;
            ctx.getSource().sendSuccess(() -> Component.literal("§aИнвентарь " + name(target) + " восстановлен."), true);
        }
        return n;
    }

    private static int clearState(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        int n = 0;
        for (ServerPlayer target : targets) {
            if (blockSelf(ctx.getSource(), target, "освободить")) {
                continue;
            }
            PrisonerData data = target.getData(ModAttachments.PRISONER.get());
            data.applySpawn(target);
            JailManager.restoreGameMode(target, data);
            data.setJailed(false);
            data.setRemainingMillis(0);
            target.setData(ModAttachments.PRISONER.get(), data);
            JailSavedData saved = JailSavedData.get(ctx.getSource().getServer());
            saved.removePrisoner(target.getUUID());
            JailDimension.sendToRelease(target, ctx.getSource().getServer().overworld(),
                    Config.RELEASE_X.get() + 0.5, Config.RELEASE_Y.get(), Config.RELEASE_Z.get() + 0.5);
            n++;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§eСтатус тюрьмы для " + name(target) + " сброшен (инвентарь НЕ восстановлен — используйте restoreinv)."), true);
        }
        return n;
    }

    private static int clearOffenses(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "targets");
        JailSavedData saved = JailSavedData.get(ctx.getSource().getServer());
        int n = 0;
        for (GameProfile profile : targets) {
            if (blockSelf(ctx.getSource(), profile.getId(), "очищать нарушения у")) {
                continue;
            }
            saved.clearOffenses(profile.getId());
            n++;
            ctx.getSource().sendSuccess(() -> Component.literal("§aИстория нарушений " + profile.getName() + " очищена."), true);
        }
        return n;
    }

    private static int releaseOffline(CommandContext<CommandSourceStack> ctx) {
        String pname = StringArgumentType.getString(ctx, "name");
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        ServerPlayer online = server.getPlayerList().getPlayerByName(pname);
        if (online != null) {
            if (blockSelf(ctx.getSource(), online.getUUID(), "освободить")) {
                return 0;
            }
            if (JailManager.isJailed(online)) {
                JailManager.release(online, true, false,
                        Component.literal("§aВы были освобождены администратором."));
                ctx.getSource().sendSuccess(() -> Component.literal("§a" + online.getGameProfile().getName() + " освобождён."), true);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.literal("§e" + online.getGameProfile().getName() + " не в тюрьме."));
                return 0;
            }
        }
        Optional<GameProfile> profile = server.getProfileCache() == null
                ? Optional.empty() : server.getProfileCache().get(pname);
        UUID id;
        final String finalName;
        if (profile.isPresent()) {
            id = profile.get().getId();
            finalName = profile.get().getName();
        } else if (!server.usesAuthentication()) {
            id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + pname).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            finalName = pname;
        } else {
            ctx.getSource().sendFailure(Component.literal("§cИгрок '" + pname + "' не найден."));
            return 0;
        }

        if (blockSelf(ctx.getSource(), id, "освободить")) {
            return 0;
        }

        boolean handled = false;
        if (saved.isPendingArrest(id)) {
            saved.clearPendingArrest(id);
            ctx.getSource().sendSuccess(() -> Component.literal("§aЗапланированный арест для " + finalName + " отменён."), true);
            handled = true;
        }
        if (saved.prisoners().containsKey(id)) {
            JailManager.requestOfflineRelease(server, id);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§a" + finalName + " будет освобождён и получит вещи при следующем входе."), true);
            handled = true;
        }
        if (!handled) {
            ctx.getSource().sendFailure(Component.literal("§e" + finalName + " не в тюрьме и не ожидает ареста."));
            return 0;
        }
        return 1;
    }

    // ---- Confiscation -----------------------------------------------------------------------

    private static int confiscate(CommandContext<CommandSourceStack> ctx, Boolean overrideSilent)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer moderator = src.getPlayerOrException(); // a GUI needs a real player
        String pname = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = src.getServer();
        JailSavedData saved = JailSavedData.get(server);

        UUID targetId;
        String targetName;
        ServerPlayer online = server.getPlayerList().getPlayerByName(pname);
        if (online != null) {
            if (!JailManager.isJailed(online)) {
                src.sendFailure(Component.literal("§e" + name(online) + " не в тюрьме."));
                return 0;
            }
            targetId = online.getUUID();
            targetName = name(online);
        } else {
            Optional<GameProfile> profile = server.getProfileCache() == null
                    ? Optional.empty() : server.getProfileCache().get(pname);
            if (profile.isEmpty()) {
                src.sendFailure(Component.literal("§cИгрок '" + pname + "' не найден."));
                return 0;
            }
            targetId = profile.get().getId();
            targetName = profile.get().getName();
            if (saved.isPendingArrest(targetId)) {
                src.sendFailure(Component.literal("§e" + targetName + " ожидает ареста при следующем входе. Инвентарь будет изъят при подключении."));
                return 0;
            }
            if (!saved.prisoners().containsKey(targetId)) {
                src.sendFailure(Component.literal("§e" + targetName + " не в тюрьме."));
                return 0;
            }
        }

        if (moderator.getUUID().equals(targetId)) {
            src.sendFailure(Component.literal("§cНельзя открыть собственный инвентарь."));
            return 0;
        }

        boolean canSilent = JailPermissions.canSilent(moderator);
        boolean silent;
        if (overrideSilent != null && canSilent) {
            silent = overrideSilent;
        } else {
            silent = canSilent && saved.isSilentByDefault(moderator.getUUID());
        }

        ConfiscationMenu.open(moderator, targetId, targetName, silent);
        return 1;
    }

    private static int setSilentDefault(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer moderator = ctx.getSource().getPlayerOrException();
        boolean value = BoolArgumentType.getBool(ctx, "value");
        JailSavedData.get(ctx.getSource().getServer()).setSilentByDefault(moderator.getUUID(), value);
        ctx.getSource().sendSuccess(() -> Component.literal(value
                ? "§aТихий режим конфискации включён: ваши изъятия по умолчанию без оповещения."
                : "§aТихий режим конфискации выключен: при ваших изъятиях придёт оповещение."), false);
        return 1;
    }

    private static final SimpleDateFormat LOG_DATE = new SimpleDateFormat("dd.MM HH:mm");

    private static int confiscationLog(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack s = ctx.getSource();
        List<JailSavedData.LogEntry> log = JailSavedData.get(s.getServer()).log(); // newest first
        if (log.isEmpty()) {
            line(s, "§7Журнал конфискаций пуст.");
            return 0;
        }
        int perPage = 10;
        int pages = (log.size() + perPage - 1) / perPage;
        int p = Math.max(1, Math.min(page, pages));
        line(s, "§6Журнал конфискаций §7(стр. " + p + "/" + pages + "):");
        int start = (p - 1) * perPage;
        int end = Math.min(start + perPage, log.size());
        for (int i = start; i < end; i++) {
            JailSavedData.LogEntry e = log.get(i);
            line(s, "§7" + LOG_DATE.format(new Date(e.time)) + " §e" + e.moderator
                    + " §7забрал у §e" + e.prisoner + "§7: §f" + e.item + " §7×" + e.count);
        }
        return 1;
    }

    // ---- Jail configuration executors -------------------------------------------------------

    private static int visitJail(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!JailDimension.adminVisit(player)) {
            ctx.getSource().sendFailure(Component.literal("§cИзмерение тюрьмы не загружено."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aВы телепортированы в тюрьму."), false);
        return 1;
    }

    /** Returns the admin to where they were before their last {@code visit} (same dimension). */
    private static int backFromVisit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int result = JailDimension.adminReturn(player);
        if (result == 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cНет сохранённой точки возврата. Сначала используйте §6/goidajail visit§c."));
            return 0;
        }
        if (result < 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cИзмерение, откуда вы пришли, больше не загружено."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aВы возвращены на прежнее место."), false);
        return 1;
    }

    /**
     * Records a pending corner (1 or 2) into the admin's in-memory selection. Does NOT change
     * the active boundary — that only happens on {@code applyregion}. While a selection exists
     * the admin sees it highlighted with particles (must be in the jail dimension to see them).
     */
    private static int setPos(CommandContext<CommandSourceStack> ctx, int corner) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        JailDimension.PendingRegion r = JailDimension.editing(player.getUUID());
        if (corner == 1) { r.x1 = pos.getX(); r.y1 = pos.getY(); r.z1 = pos.getZ(); }
        else            { r.x2 = pos.getX(); r.y2 = pos.getY(); r.z2 = pos.getZ(); }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aТочка " + corner + " выделена: §e(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                + (r.complete()
                        ? " §7— обе точки заданы, примените: §6/goidajail applyregion"
                        : " §7— задайте вторую точку (setpos" + (corner == 1 ? "2" : "1") + ")")), false);
        if (!JailDimension.isJailLevel(player.level().dimension())) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7(подсветка видна в измерении тюрьмы — /goidajail visit)"), false);
        }
        return 1;
    }

    /** Commits the admin's pending selection to the saved boundary and activates all its logic. */
    private static int applyRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        JailDimension.PendingRegion r = JailDimension.pendingOf(player.getUUID());
        if (r == null || !r.complete()) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cСначала выделите обе точки: §6/goidajail setpos1§c и §6/goidajail setpos2§c."));
            return 0;
        }
        int x1 = Math.min(r.x1, r.x2), y1 = Math.min(r.y1, r.y2), z1 = Math.min(r.z1, r.z2);
        int x2 = Math.max(r.x1, r.x2), y2 = Math.max(r.y1, r.y2), z2 = Math.max(r.z1, r.z2);
        if (x2 - x1 + 1 < 3 || y2 - y1 + 1 < 3 || z2 - z1 + 1 < 3) {
            ctx.getSource().sendFailure(Component.literal("§cРегион слишком мал. Минимум 3×3×3 блоков."));
            return 0;
        }
        if (x2 - x1 > 200 || y2 - y1 > 200 || z2 - z1 > 200) {
            ctx.getSource().sendFailure(Component.literal("§cРегион слишком большой (максимум 200×200×200)."));
            return 0;
        }
        JailSavedData saved = JailSavedData.get(server);
        saved.setBox(x1, y1, z1, x2, y2, z2);
        JailDimension.clearPending(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aГраницы тюрьмы приняты: §e(" + x1 + ", " + y1 + ", " + z1
                + ")§a → §e(" + x2 + ", " + y2 + ", " + z2 + ")"), true);
        recenterIfNeeded(ctx.getSource(), server, saved);
        return 1;
    }

    /** Discards the admin's pending selection (and stops its highlight). */
    private static int clearRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (JailDimension.pendingOf(player.getUUID()) == null) {
            ctx.getSource().sendFailure(Component.literal("§eУ вас нет активного выделения."));
            return 0;
        }
        JailDimension.clearPending(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aВыделение очищено. Текущие границы тюрьмы не изменились."), false);
        return 1;
    }

    /**
     * After the boundary changes: if the spawn now falls outside it, reset the spawn to the box
     * centre (and tell the admin). Then move any current prisoners onto the valid spawn.
     */
    private static void recenterIfNeeded(CommandSourceStack src, MinecraftServer server, JailSavedData saved) {
        if (!withinBox(saved, saved.getJailSpawnX(), saved.getJailSpawnY(), saved.getJailSpawnZ())) {
            int x1 = saved.getBoxX1(), y1 = saved.getBoxY1(), z1 = saved.getBoxZ1();
            int x2 = saved.getBoxX2(), z2 = saved.getBoxZ2();
            double cx = (x1 + x2) / 2.0 + 0.5, cy = y1 + 1.0, cz = (z1 + z2) / 2.0 + 0.5;
            saved.setJailSpawn(cx, cy, cz);
            src.sendSuccess(() -> Component.literal(
                    "§eТочка спавна была вне новых границ — сброшена в центр: §e("
                    + cx + ", " + cy + ", " + cz + ")"), false);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (JailManager.isJailed(p)) JailDimension.recenter(p);
        }
    }

    private static int setJailSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        MinecraftServer server = ctx.getSource().getServer();
        JailSavedData saved = JailSavedData.get(server);
        double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;
        // Reject a spawn outside the boundary: it would make the position guard re-teleport the
        // prisoner to it every tick (the AABB check would fail immediately after arrival).
        if (!withinBox(saved, x, y, z)) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cТочка спавна вне границ тюрьмы (" + saved.getBoxX1() + ".." + saved.getBoxX2()
                    + ", " + saved.getBoxY1() + ".." + saved.getBoxY2()
                    + ", " + saved.getBoxZ1() + ".." + saved.getBoxZ2() + "). "
                    + "Сначала задайте границы (setpos1/setpos2) или выберите точку внутри них."));
            return 0;
        }
        saved.setJailSpawn(x, y, z);
        // Move current prisoners onto the new spawn immediately.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (JailManager.isJailed(p)) JailDimension.recenter(p);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aТочка спавна тюрьмы: §e(" + x + ", " + y + ", " + z + ")"), true);
        return 1;
    }

    /** True if (x,y,z) lies inside the saved box volume [box1 .. box2+1) on every axis. */
    private static boolean withinBox(JailSavedData s, double x, double y, double z) {
        return x >= s.getBoxX1() && x <= s.getBoxX2() + 1
                && y >= s.getBoxY1() && y <= s.getBoxY2() + 1
                && z >= s.getBoxZ1() && z <= s.getBoxZ2() + 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        line(s, "§6§l=== GoidaJail — справка ===");
        line(s, "§eДубинка§7: ударьте игрока предметом, чтобы посадить его в тюрьму.");
        line(s, "§7Срок: 15 мин за 1-е нарушение, +15 мин за каждое следующее (учёт 3 дня).");
        line(s, "§7Время идёт только пока игрок онлайн. Вещи изымаются и возвращаются при выходе.");
        line(s, "");
        line(s, "§6/goidajail baton §7— получить дубинку");
        line(s, "§6/jail <игрок> [минуты] §7или §6/goidajail jail <игрок> [минуты] §7— посадить (онлайн или оффлайн)");
        line(s, "§6/jailoffline <ник> [минуты] §7— запланировать арест оффлайн-игрока на след. вход");
        line(s, "§6/unjail <игрок> §7или §6/goidajail release <игрок> §7— освободить (онлайн или оффлайн)");
        line(s, "§6/unjailoffline <ник> §7или §6/goidajail releaseoffline <ник> §7— освободить при след. входе");
        line(s, "§6/goidajail pardon <игрок> §7— освободить + СНЯТЬ нарушение (ошибочный арест)");
        line(s, "§6/goidajail time <игрок> <минуты> §7— задать оставшийся срок (онлайн/оффлайн)");
        line(s, "§6/goidajail addtime <игрок> <минуты> §7— добавить/убавить срок (онлайн/оффлайн)");
        line(s, "§6/goidajail info <игрок> §7— статус, срок, число нарушений, есть ли бэкап");
        line(s, "§6/goidajail list §7— список заключённых (●онлайн / ○офлайн / ⏳ожидают ареста)");
        line(s, "§6/goidajail restoreinv <игрок> §7— §cвосстановление§7: вернуть вещи из бэкапа");
        line(s, "§6/goidajail clearstate <игрок> §7— §cаварийно§7: снять статус тюрьмы без возврата вещей");
        line(s, "§6/goidajail clearoffenses <игрок> §7— очистить историю нарушений");
        line(s, "");
        line(s, "§e— Конфискация (отдельные права) —");
        line(s, "§6/goidajail confiscate <ник> [notify|silent] §7— открыть инвентарь заключённого");
        line(s, "§7  (онлайн/офлайн); забранное не возвращается, остальное вернётся в свои слоты.");
        line(s, "§6/goidajail confiscatesilent <on|off> §7— тихий режим по умолчанию (без оповещения)");
        line(s, "§6/goidajail confiscationlog [стр] §7— журнал: кто у кого что и сколько забрал");
        line(s, "");
        line(s, "");
        line(s, "§e— Настройка тюрьмы (только для goidajail.use) —");
        line(s, "§6/goidajail visit §7— телепортироваться в тюрьму (точка задаётся в конфиге: visitX/Y/Z)");
        line(s, "§6/goidajail back §7— вернуться туда (и в то измерение), откуда применили visit");
        line(s, "§6/goidajail setpos1 <x y z> §7— выделить точку 1 (подсветка партиклами, в тюрьме)");
        line(s, "§6/goidajail setpos2 <x y z> §7— выделить точку 2 (область подсвечивается целиком)");
        line(s, "§6/goidajail applyregion §7— принять выделение → границы сохраняются и начинают работать");
        line(s, "§6/goidajail clearregion §7— очистить выделение (границы не меняются)");
        line(s, "§6/goidajail setjailspawn <x y z> §7— изменить точку появления заключённых");
        line(s, "");
        line(s, "§7Права (LuckPerms / FTB Ranks, выдаются независимо):");
        line(s, "§f  goidajail.use (или command.goidajail в FTB Ranks) §7— базовые команды и дубинка (op-уровень " + Config.BATON_PERMISSION_LEVEL.get() + ").");
        line(s, "§f  goidajail.confiscate §7— открывать инвентарь заключённого.");
        line(s, "§f  goidajail.confiscate.silent §7— конфисковать без оповещения игрока.");
        line(s, "§f  goidajail.confiscate.log §7— смотреть журнал конфискаций.");
        return 1;
    }

    private static void line(CommandSourceStack s, String text) {
        s.sendSuccess(() -> Component.literal(text), false);
    }

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    /** True if the command source is a player who is currently jailed. */
    private static boolean isSourceJailed(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p && JailManager.isJailed(p);
    }

    /**
     * Blocks a player from targeting themselves with a state-changing command (you can neither
     * jail nor free yourself). Console and command blocks have no entity and always pass.
     */
    private static boolean blockSelf(CommandSourceStack src, UUID targetId, String action) {
        if (src.getEntity() instanceof ServerPlayer p && p.getUUID().equals(targetId)) {
            src.sendFailure(Component.literal("§cНельзя " + action + " самого себя."));
            return true;
        }
        return false;
    }

    private static boolean blockSelf(CommandSourceStack src, ServerPlayer target, String action) {
        return blockSelf(src, target.getUUID(), action);
    }
}
