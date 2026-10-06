package com.goida.goidajail.menu;

import com.goida.goidajail.GoidaJail;
import com.goida.goidajail.jail.ConfiscatedInventory;
import com.goida.goidajail.jail.ConfiscatedInventory.Origin;
import com.goida.goidajail.jail.ConfiscatedInventory.Section;
import com.goida.goidajail.jail.JailSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side, vanilla-rendered chest GUI (6×9) over a jailed player's confiscated inventory.
 *
 * <p>Backed by a {@link SimpleContainer}; the moderator takes/puts items like a normal chest.
 * On close ({@link #removed}) the container is written back to {@link JailSavedData}; everything
 * the moderator took is logged (always, even in silent mode) and — unless silent — queued as a
 * notification delivered to the prisoner when they leave jail. Works identically for online and
 * offline prisoners since the data lives in level storage.
 */
public final class ConfiscationMenu extends ChestMenu {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9; // 54

    private final SimpleContainer chest;
    private final MinecraftServer server;
    private final UUID targetId;
    private final String targetName;
    private final String moderatorName;
    private final boolean silent;
    private final List<Origin> layout;
    private final int pageStartIndex;
    private final int shownCount;
    private final List<ItemStack> before;

    private ConfiscationMenu(int id, Inventory playerInv, SimpleContainer container,
                             MinecraftServer server, UUID targetId, String targetName,
                             String moderatorName, boolean silent, List<Origin> layout,
                             int pageStartIndex, int shownCount, List<ItemStack> before) {
        super(MenuType.GENERIC_9x6, id, playerInv, container, ROWS);
        this.chest = container;
        this.server = server;
        this.targetId = targetId;
        this.targetName = targetName;
        this.moderatorName = moderatorName;
        this.silent = silent;
        this.layout = layout;
        this.pageStartIndex = pageStartIndex;
        this.shownCount = shownCount;
        this.before = before;
    }

    /** Opens page 1 of the confiscation window for the moderator over the target's stored inventory. */
    public static void open(ServerPlayer moderator, UUID targetId, String targetName, boolean silent) {
        open(moderator, targetId, targetName, silent, 1);
    }

    /** Opens a specific page of the confiscation window for the moderator over the target's stored inventory. */
    public static void open(ServerPlayer moderator, UUID targetId, String targetName, boolean silent, int requestedPage) {
        MinecraftServer server = moderator.server;
        JailSavedData saved = JailSavedData.get(server);
        ConfiscatedInventory inv = saved.getConfiscated(targetId);
        if (inv == null) {
            moderator.sendSystemMessage(Component.literal(
                    "§cНет данных об инвентаре игрока " + targetName + "."));
            return;
        }

        List<Origin> layout = inv.buildLayout();
        int totalPages = Math.max(1, (layout.size() + SIZE - 1) / SIZE);
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int startIndex = (page - 1) * SIZE;
        int endIndex = Math.min(page * SIZE, layout.size());
        int shown = Math.max(0, endIndex - startIndex);

        SimpleContainer container = new SimpleContainer(SIZE);
        List<ItemStack> before = new ArrayList<>(shown);
        for (int k = 0; k < shown; k++) {
            ItemStack stack = inv.get(layout.get(startIndex + k)).copy();
            container.setItem(k, stack);
            before.add(stack.copy());
        }

        Component title;
        if (totalPages > 1) {
            title = Component.literal("§8Инвентарь: §f" + targetName + " §7(" + page + "/" + totalPages + ")");
            moderator.sendSystemMessage(Component.literal("§7Инвентарь игрока §f" + targetName
                    + " §7содержит §f" + layout.size() + "§7 слотов (§e" + page + "/" + totalPages + " стр.§7). "
                    + "Для выбора страницы: §e/goidajail confiscate " + targetName
                    + (silent ? " silent " : " ") + "<стр>"));
        } else {
            title = Component.literal("§8Инвентарь: §f" + targetName);
        }

        moderator.openMenu(new SimpleMenuProvider(
                (id, pinv, p) -> new ConfiscationMenu(id, pinv, container, server, targetId,
                        targetName, p.getGameProfile().getName(), silent, layout, startIndex, shown, before),
                title));
    }

    @Override
    public void removed(Player player) {
        try {
            persist(player);
        } catch (Exception e) {
            GoidaJail.LOGGER.error("Failed to persist confiscation of {} by {}", targetName, moderatorName, e);
        }
        super.removed(player);
    }

    private void persist(Player closer) {
        JailSavedData saved = JailSavedData.get(server);
        SimpleContainer c = this.chest;

        ConfiscatedInventory inv = saved.getConfiscated(targetId);
        if (inv == null) {
            // Prisoner was released while the window was open (rare race). Rebuild a fresh entry
            // from the layout so nothing crashes; it is cleaned up on the player's next login.
            inv = new ConfiscatedInventory(sectionSize(Section.CURIOS), sectionSize(Section.COSMETIC), sectionSize(Section.BACKPACKED));
        }

        // Write the (possibly edited) shown slots back into storage.
        for (int k = 0; k < shownCount; k++) {
            inv.set(layout.get(pageStartIndex + k), c.getItem(k).copy());
        }
        // Return any items the moderator dropped into filler slots — never lose them.
        for (int k = shownCount; k < c.getContainerSize(); k++) {
            ItemStack filler = c.getItem(k);
            if (!filler.isEmpty()) {
                giveOrDrop(closer, filler);
                c.setItem(k, ItemStack.EMPTY);
            }
        }
        saved.putConfiscated(targetId, inv);

        // Diff before vs after to find what the moderator took.
        List<ItemStack> after = new ArrayList<>(shownCount);
        for (int k = 0; k < shownCount; k++) after.add(c.getItem(k));
        List<ItemStack> taken = computeTaken(before, after);

        long now = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        for (ItemStack t : taken) {
            String itemName = t.getHoverName().getString();
            // Audit log: ALWAYS, even in silent mode.
            saved.addLog(new JailSavedData.LogEntry(now, moderatorName, targetName, itemName, t.getCount()));
            notes.add(formatNotification(itemName, t.getCount()));
        }
        if (!silent) {
            saved.addPendingNotifications(targetId, notes);
        }

        // Flush level data so the confiscation survives an unexpected crash.
        server.overworld().getDataStorage().save();
    }

    private int sectionSize(Section section) {
        int size = 0;
        for (Origin o : layout) {
            if (o.section() == section) size = Math.max(size, o.index() + 1);
        }
        return size;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (player instanceof ServerPlayer sp) {
            if (!sp.getInventory().add(stack)) {
                sp.drop(stack, false);
            }
        }
    }

    /** Aggregated list of items present in {@code before} but missing from {@code after}. */
    private static List<ItemStack> computeTaken(List<ItemStack> before, List<ItemStack> after) {
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack s : after) {
            if (!s.isEmpty()) pool.add(s.copy());
        }
        List<ItemStack> taken = new ArrayList<>();
        for (ItemStack b : before) {
            if (b.isEmpty()) continue;
            int remaining = b.getCount();
            for (ItemStack p : pool) {
                if (remaining <= 0) break;
                if (!p.isEmpty() && ItemStack.isSameItemSameComponents(b, p)) {
                    int sub = Math.min(remaining, p.getCount());
                    p.shrink(sub);
                    remaining -= sub;
                }
            }
            if (remaining > 0) {
                boolean merged = false;
                for (ItemStack t : taken) {
                    if (ItemStack.isSameItemSameComponents(t, b)) {
                        t.grow(remaining);
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    ItemStack rep = b.copy();
                    rep.setCount(remaining);
                    taken.add(rep);
                }
            }
        }
        return taken;
    }

    private static String formatNotification(String itemName, int count) {
        if (count <= 1) {
            return "§e[Тюрьма] §fПредмет §6" + itemName + "§f был конфискован.";
        }
        return "§e[Тюрьма] §fПредмет §6" + itemName + "§f в количестве §6" + count + "§f штук был конфискован.";
    }
}
