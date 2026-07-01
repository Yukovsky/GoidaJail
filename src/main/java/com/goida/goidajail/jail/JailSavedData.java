package com.goida.goidajail.jail;

import com.goida.goidajail.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Level-wide jail bookkeeping stored on the overworld (the dimension that is never unloaded):
 * <ul>
 *     <li>offense history with a configurable retention window for escalating sentences,</li>
 *     <li>a redundant prisoner registry for listing / recovery even when a player is offline,</li>
 *     <li>a "pending release" set so operators can free offline players, applied on next login.</li>
 * </ul>
 */
public final class JailSavedData extends SavedData {

    private static final String NAME = "goidajail_data";

    public static final class Offense {
        public int count;
        public long lastEpoch;
        Offense(int count, long lastEpoch) { this.count = count; this.lastEpoch = lastEpoch; }
    }

    public static final class PrisonerSummary {
        public String name;
        public long remainingMillis;
        public long totalMillis;
        PrisonerSummary(String name, long remainingMillis, long totalMillis) {
            this.name = name; this.remainingMillis = remainingMillis; this.totalMillis = totalMillis;
        }
    }

    /** One audit-log row: who confiscated what from whom, and when. */
    public static final class LogEntry {
        public long time;
        public String moderator;
        public String prisoner;
        public String item;
        public int count;
        public LogEntry(long time, String moderator, String prisoner, String item, int count) {
            this.time = time; this.moderator = moderator; this.prisoner = prisoner;
            this.item = item; this.count = count;
        }
    }

    private static final int LOG_CAP = 200;

    private final Map<UUID, Offense> offenses = new HashMap<>();
    private final Map<UUID, PrisonerSummary> prisoners = new HashMap<>();
    private final Set<UUID> pendingRelease = new HashSet<>();

    // Confiscation feature.
    private final Map<UUID, ConfiscatedInventory> confiscated = new HashMap<>();
    private final Set<UUID> silentByDefault = new HashSet<>();
    private final Map<UUID, List<String>> pendingNotifications = new HashMap<>();
    private final List<LogEntry> confiscationLog = new ArrayList<>();

    // Jail box configuration — outer corners of the bedrock structure.
    // Defaults match the legacy hardcoded geometry (WALL=3, INTERIOR_HALF=7, Y 64-70).
    private int boxX1 = -10, boxY1 = 64, boxZ1 = -10;
    private int boxX2 = 10,  boxY2 = 70, boxZ2 = 10;
    private double jailSpawnX = 0.5, jailSpawnY = 65.0, jailSpawnZ = 0.5;
    // false = box has never been built yet (fresh world); true = already built, never rebuild.
    private boolean boxBuilt = false;

    public JailSavedData() {}

    public static JailSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(JailSavedData::new, JailSavedData::load), NAME);
    }

    // ---- Jail box configuration -----------------------------------------------------------

    public int getBoxX1() { return boxX1; }
    public int getBoxY1() { return boxY1; }
    public int getBoxZ1() { return boxZ1; }
    public int getBoxX2() { return boxX2; }
    public int getBoxY2() { return boxY2; }
    public int getBoxZ2() { return boxZ2; }
    public double getJailSpawnX() { return jailSpawnX; }
    public double getJailSpawnY() { return jailSpawnY; }
    public double getJailSpawnZ() { return jailSpawnZ; }

    /** Stores the two corners normalised so box1 is always the min and box2 the max corner. */
    public void setBox(int ax, int ay, int az, int bx, int by, int bz) {
        boxX1 = Math.min(ax, bx); boxY1 = Math.min(ay, by); boxZ1 = Math.min(az, bz);
        boxX2 = Math.max(ax, bx); boxY2 = Math.max(ay, by); boxZ2 = Math.max(az, bz);
        setDirty();
    }

    public void setJailSpawn(double x, double y, double z) {
        jailSpawnX = x; jailSpawnY = y; jailSpawnZ = z;
        setDirty();
    }

    public boolean isBoxBuilt() { return boxBuilt; }

    public void markBoxBuilt() { boxBuilt = true; setDirty(); }

    // ---- Offenses -------------------------------------------------------------------------

    /** Returns the current (non-expired) offense count without modifying anything. */
    public int currentOffenseCount(UUID id, long now) {
        Offense o = offenses.get(id);
        if (o == null) return 0;
        if (now - o.lastEpoch > Config.offenseExpiryMillis()) return 0;
        return o.count;
    }

    /** Registers a new offense (resetting if the previous one expired) and returns the new count. */
    public int registerOffense(UUID id, long now) {
        Offense o = offenses.get(id);
        if (o == null || now - o.lastEpoch > Config.offenseExpiryMillis()) {
            o = new Offense(0, now);
            offenses.put(id, o);
        }
        o.count += 1;
        o.lastEpoch = now;
        setDirty();
        return o.count;
    }

    /** Undo the most recent offense increment (used by {@code /goidajail pardon}). */
    public void refundOffense(UUID id) {
        Offense o = offenses.get(id);
        if (o == null) return;
        o.count -= 1;
        if (o.count <= 0) offenses.remove(id);
        setDirty();
    }

    public void clearOffenses(UUID id) {
        if (offenses.remove(id) != null) setDirty();
    }

    // ---- Prisoner registry ----------------------------------------------------------------

    public void updatePrisoner(UUID id, String name, long remainingMillis, long totalMillis) {
        prisoners.put(id, new PrisonerSummary(name, remainingMillis, totalMillis));
        setDirty();
    }

    public void removePrisoner(UUID id) {
        if (prisoners.remove(id) != null) setDirty();
    }

    public Map<UUID, PrisonerSummary> prisoners() {
        return prisoners;
    }

    // ---- Pending offline release ----------------------------------------------------------

    public void addPendingRelease(UUID id) { pendingRelease.add(id); setDirty(); }
    public boolean isPendingRelease(UUID id) { return pendingRelease.contains(id); }
    public void clearPendingRelease(UUID id) { if (pendingRelease.remove(id)) setDirty(); }
    public Collection<UUID> pendingReleases() { return pendingRelease; }

    // ---- Confiscated inventory ------------------------------------------------------------

    @Nullable
    public ConfiscatedInventory getConfiscated(UUID id) { return confiscated.get(id); }

    public void putConfiscated(UUID id, ConfiscatedInventory inv) { confiscated.put(id, inv); setDirty(); }

    public void removeConfiscated(UUID id) { if (confiscated.remove(id) != null) setDirty(); }

    public boolean hasConfiscated(UUID id) { return confiscated.containsKey(id); }

    // ---- Silent-mode default (per moderator) ----------------------------------------------

    public boolean isSilentByDefault(UUID moderator) { return silentByDefault.contains(moderator); }

    public void setSilentByDefault(UUID moderator, boolean silent) {
        boolean changed = silent ? silentByDefault.add(moderator) : silentByDefault.remove(moderator);
        if (changed) setDirty();
    }

    // ---- Pending confiscation notifications (delivered on release) -------------------------

    public void addPendingNotifications(UUID prisoner, List<String> messages) {
        if (messages.isEmpty()) return;
        pendingNotifications.computeIfAbsent(prisoner, k -> new ArrayList<>()).addAll(messages);
        setDirty();
    }

    /** Returns and removes all queued messages for the prisoner. */
    public List<String> takePendingNotifications(UUID prisoner) {
        List<String> msgs = pendingNotifications.remove(prisoner);
        if (msgs != null) setDirty();
        return msgs == null ? Collections.emptyList() : msgs;
    }

    public void clearPendingNotifications(UUID prisoner) {
        if (pendingNotifications.remove(prisoner) != null) setDirty();
    }

    // ---- Confiscation audit log -----------------------------------------------------------

    public void addLog(LogEntry entry) {
        confiscationLog.add(entry);
        while (confiscationLog.size() > LOG_CAP) confiscationLog.remove(0);
        setDirty();
    }

    /** Newest first. */
    public List<LogEntry> log() {
        List<LogEntry> copy = new ArrayList<>(confiscationLog);
        Collections.reverse(copy);
        return copy;
    }

    // ---- Persistence ----------------------------------------------------------------------

    public static JailSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        JailSavedData data = new JailSavedData();
        try {
            ListTag offenseList = tag.getList("offenses", Tag.TAG_COMPOUND);
            for (int i = 0; i < offenseList.size(); i++) {
                CompoundTag e = offenseList.getCompound(i);
                data.offenses.put(UUID.fromString(e.getString("id")),
                        new Offense(e.getInt("count"), e.getLong("lastEpoch")));
            }
            ListTag prisonerList = tag.getList("prisoners", Tag.TAG_COMPOUND);
            for (int i = 0; i < prisonerList.size(); i++) {
                CompoundTag e = prisonerList.getCompound(i);
                data.prisoners.put(UUID.fromString(e.getString("id")),
                        new PrisonerSummary(e.getString("name"), e.getLong("remaining"), e.getLong("total")));
            }
            ListTag pending = tag.getList("pendingRelease", Tag.TAG_STRING);
            for (int i = 0; i < pending.size(); i++) {
                data.pendingRelease.add(UUID.fromString(pending.getString(i)));
            }

            ListTag confList = tag.getList("confiscated", Tag.TAG_COMPOUND);
            for (int i = 0; i < confList.size(); i++) {
                CompoundTag e = confList.getCompound(i);
                data.confiscated.put(UUID.fromString(e.getString("id")),
                        ConfiscatedInventory.load(e.getCompound("inv"), registries));
            }

            ListTag silentList = tag.getList("silentByDefault", Tag.TAG_STRING);
            for (int i = 0; i < silentList.size(); i++) {
                data.silentByDefault.add(UUID.fromString(silentList.getString(i)));
            }

            ListTag notifyList = tag.getList("pendingNotifications", Tag.TAG_COMPOUND);
            for (int i = 0; i < notifyList.size(); i++) {
                CompoundTag e = notifyList.getCompound(i);
                List<String> msgs = new ArrayList<>();
                ListTag ml = e.getList("messages", Tag.TAG_STRING);
                for (int j = 0; j < ml.size(); j++) msgs.add(ml.getString(j));
                data.pendingNotifications.put(UUID.fromString(e.getString("id")), msgs);
            }

            ListTag logList = tag.getList("confiscationLog", Tag.TAG_COMPOUND);
            for (int i = 0; i < logList.size(); i++) {
                CompoundTag e = logList.getCompound(i);
                data.confiscationLog.add(new LogEntry(e.getLong("time"), e.getString("mod"),
                        e.getString("prisoner"), e.getString("item"), e.getInt("count")));
            }
            if (tag.contains("boxX1")) {
                data.boxX1 = tag.getInt("boxX1"); data.boxY1 = tag.getInt("boxY1"); data.boxZ1 = tag.getInt("boxZ1");
                data.boxX2 = tag.getInt("boxX2"); data.boxY2 = tag.getInt("boxY2"); data.boxZ2 = tag.getInt("boxZ2");
                data.jailSpawnX = tag.getDouble("spawnX");
                data.jailSpawnY = tag.getDouble("spawnY");
                data.jailSpawnZ = tag.getDouble("spawnZ");
            }
            // Worlds created before this field existed already have the box — treat as built.
            data.boxBuilt = tag.contains("boxBuilt") ? tag.getBoolean("boxBuilt") : true;
        } catch (Exception ex) {
            // Never throw during load: a partially corrupt file must not break the server.
            com.goida.goidajail.GoidaJail.LOGGER.error("Corrupted {} file; loaded what was parseable.", NAME, ex);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag offenseList = new ListTag();
        offenses.forEach((id, o) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id.toString());
            e.putInt("count", o.count);
            e.putLong("lastEpoch", o.lastEpoch);
            offenseList.add(e);
        });
        tag.put("offenses", offenseList);

        ListTag prisonerList = new ListTag();
        prisoners.forEach((id, p) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id.toString());
            e.putString("name", p.name);
            e.putLong("remaining", p.remainingMillis);
            e.putLong("total", p.totalMillis);
            prisonerList.add(e);
        });
        tag.put("prisoners", prisonerList);

        ListTag pending = new ListTag();
        pendingRelease.forEach(id -> pending.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
        tag.put("pendingRelease", pending);

        ListTag confList = new ListTag();
        confiscated.forEach((id, inv) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id.toString());
            e.put("inv", inv.serializeNBT(registries));
            confList.add(e);
        });
        tag.put("confiscated", confList);

        ListTag silentList = new ListTag();
        silentByDefault.forEach(id -> silentList.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
        tag.put("silentByDefault", silentList);

        ListTag notifyList = new ListTag();
        pendingNotifications.forEach((id, msgs) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id.toString());
            ListTag ml = new ListTag();
            msgs.forEach(m -> ml.add(net.minecraft.nbt.StringTag.valueOf(m)));
            e.put("messages", ml);
            notifyList.add(e);
        });
        tag.put("pendingNotifications", notifyList);

        ListTag logList = new ListTag();
        confiscationLog.forEach(en -> {
            CompoundTag e = new CompoundTag();
            e.putLong("time", en.time);
            e.putString("mod", en.moderator);
            e.putString("prisoner", en.prisoner);
            e.putString("item", en.item);
            e.putInt("count", en.count);
            logList.add(e);
        });
        tag.put("confiscationLog", logList);

        tag.putInt("boxX1", boxX1); tag.putInt("boxY1", boxY1); tag.putInt("boxZ1", boxZ1);
        tag.putInt("boxX2", boxX2); tag.putInt("boxY2", boxY2); tag.putInt("boxZ2", boxZ2);
        tag.putDouble("spawnX", jailSpawnX); tag.putDouble("spawnY", jailSpawnY); tag.putDouble("spawnZ", jailSpawnZ);
        tag.putBoolean("boxBuilt", boxBuilt);

        return tag;
    }
}
