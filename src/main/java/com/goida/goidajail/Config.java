package com.goida.goidajail;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side configuration. All values are read at runtime from {@code goidajail-server.toml}.
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BASE_SENTENCE_MINUTES = BUILDER
            .comment("Sentence length (minutes) applied for a player's FIRST offense.")
            .defineInRange("baseSentenceMinutes", 15, 1, 60 * 24);

    public static final ModConfigSpec.IntValue INCREMENT_MINUTES = BUILDER
            .comment("Extra minutes added for every prior offense within the retention window.",
                    "sentence = baseSentenceMinutes + (offenseCount - 1) * incrementMinutes")
            .defineInRange("incrementMinutes", 15, 0, 60 * 24);

    public static final ModConfigSpec.IntValue MAX_SENTENCE_MINUTES = BUILDER
            .comment("Hard cap on a single sentence (minutes), regardless of repeat offenses.")
            .defineInRange("maxSentenceMinutes", 180, 1, 60 * 24 * 7);

    public static final ModConfigSpec.IntValue OFFENSE_EXPIRY_HOURS = BUILDER
            .comment("How long (hours) an offense is remembered for escalating sentences.",
                    "After this window with no new offense, the counter resets to zero.")
            .defineInRange("offenseExpiryHours", 72, 1, 24 * 30);

    public static final ModConfigSpec.IntValue RELEASE_X = BUILDER
            .comment("Overworld X coordinate a freed player is teleported to.")
            .defineInRange("releaseX", 0, -30_000_000, 30_000_000);

    public static final ModConfigSpec.IntValue RELEASE_Y = BUILDER
            .comment("Overworld Y coordinate a freed player is teleported to.")
            .defineInRange("releaseY", 63, -64, 320);

    public static final ModConfigSpec.IntValue RELEASE_Z = BUILDER
            .comment("Overworld Z coordinate a freed player is teleported to.")
            .defineInRange("releaseZ", 0, -30_000_000, 30_000_000);

    public static final ModConfigSpec.IntValue VISIT_X = BUILDER
            .comment("Jail-dimension X coordinate an admin is teleported to via /goidajail visit.")
            .defineInRange("visitX", 0, -30_000_000, 30_000_000);

    public static final ModConfigSpec.IntValue VISIT_Y = BUILDER
            .comment("Jail-dimension Y coordinate an admin is teleported to via /goidajail visit.")
            .defineInRange("visitY", 65, -64, 320);

    public static final ModConfigSpec.IntValue VISIT_Z = BUILDER
            .comment("Jail-dimension Z coordinate an admin is teleported to via /goidajail visit.")
            .defineInRange("visitZ", 0, -30_000_000, 30_000_000);

    public static final ModConfigSpec.ConfigValue<String> BATON_ITEM_ID = BUILDER
            .comment("Registry id of the jail baton item. This item is registered by KubeJS",
                    "(present on clients), NOT by this mod. Hitting a player with it jails them.",
                    "Default matches the bundled KubeJS startup script.")
            .define("batonItemId", "kubejs:jail_baton");

    public static final ModConfigSpec.IntValue BATON_PERMISSION_LEVEL = BUILDER
            .comment("Minimum permission level (op level) required to use the jail baton and commands.")
            .defineInRange("batonPermissionLevel", 2, 0, 4);

    public static final ModConfigSpec.IntValue RADIO_INTERVAL_SECONDS = BUILDER
            .comment("How often (seconds) the in-jail 'radio' reminder message is shown.")
            .defineInRange("radioIntervalSeconds", 30, 5, 600);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

    // ---- Convenience millisecond accessors -------------------------------------------------

    public static long sentenceMillisFor(int offenseCount) {
        int minutes = BASE_SENTENCE_MINUTES.get() + Math.max(0, offenseCount - 1) * INCREMENT_MINUTES.get();
        minutes = Math.min(minutes, MAX_SENTENCE_MINUTES.get());
        return minutes * 60_000L;
    }

    public static long offenseExpiryMillis() {
        return OFFENSE_EXPIRY_HOURS.get() * 3_600_000L;
    }

    public static long radioIntervalMillis() {
        return RADIO_INTERVAL_SECONDS.get() * 1000L;
    }
}
