package com.spawnadmin;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side settings (world/serverconfig/spawnadmin-server.toml). */
public final class SpawnAdminConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue HIDDEN_DELAY_SECONDS;
    public static final ModConfigSpec.IntValue HORN_REST_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> TARGET_DIMENSION;
    public static final ModConfigSpec.BooleanValue BUILD_ARRIVAL_PLATFORM;
    public static final ModConfigSpec.IntValue MAX_EVENTS_PER_DIMENSION;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("summoning");
        HIDDEN_DELAY_SECONDS = b.comment("Silent wait between blowing the horn and the first crack. Nothing is visible to anyone; only the horn blower hears a clock.")
                .defineInRange("hiddenDelaySeconds", 10, 0, 600);
        HORN_REST_MINUTES = b.comment("How long (real-time minutes) the horn rests after it has carried a player into the realm. Default 300 (5 hours).")
                .defineInRange("hornRestMinutes", 300, 0, 7 * 24 * 60);
        MAX_EVENTS_PER_DIMENSION = b.comment("How many sky events may run at once in one dimension.")
                .defineInRange("maxEventsPerDimension", 3, 1, 16);
        b.pop();
        b.push("realm");
        TARGET_DIMENSION = b.comment("Dimension the hand carries players to. Defaults to the built-in administrator realm.")
                .define("targetDimension", "spawnadmin:admin_realm");
        BUILD_ARRIVAL_PLATFORM = b.comment("Build a small platform at the arrival point if it is empty.")
                .define("buildArrivalPlatform", true);
        b.pop();
        SPEC = b.build();
    }

    private SpawnAdminConfig() {
    }
}
