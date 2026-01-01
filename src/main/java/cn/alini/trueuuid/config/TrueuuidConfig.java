package cn.alini.trueuuid.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class TrueuuidConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        COMMON = new Common(b);
        COMMON_SPEC = b.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    public static long timeoutMs() { return COMMON.timeoutMs.get(); }
    public static boolean allowOfflineOnTimeout() { return COMMON.allowOfflineOnTimeout.get(); }

    // 旧开关：保留兼容，但新策略将更细化 (Old switch: Keep for compatibility, but new strategy will be more granular)
    public static boolean allowOfflineOnFailure() { return COMMON.allowOfflineOnFailure.get(); }

    public static String timeoutKickMessage() { return COMMON.timeoutKickMessage.get(); }
    public static String offlineFallbackMessage() { return COMMON.offlineFallbackMessage.get(); }

    // 新增：短副标题（用于屏幕 Title 区域） (Added: Short subtitle (for screen Title area))
    public static String offlineShortSubtitle() { return COMMON.offlineShortSubtitle.get(); }
    public static String onlineShortSubtitle() { return COMMON.onlineShortSubtitle.get(); }

    // 新增：策略相关 (Added: Strategy related)
    public static boolean knownPremiumDenyOffline() { return COMMON.knownPremiumDenyOffline.get(); }
    public static boolean allowOfflineForUnknownOnly() { return COMMON.allowOfflineForUnknownOnly.get(); }
    public static boolean recentIpGraceEnabled() { return COMMON.recentIpGraceEnabled.get(); }
    public static int recentIpGraceTtlSeconds() { return COMMON.recentIpGraceTtlSeconds.get(); }
    public static boolean debug() { return COMMON.debug.get(); }
    // 新增 nomojang 开关访问器 (Added nomojang switch accessor)
    public static boolean nomojangEnabled() { return COMMON.nomojangEnabled.get(); }

    public static final class Common {
        public final ForgeConfigSpec.LongValue timeoutMs;
        public final ForgeConfigSpec.BooleanValue allowOfflineOnTimeout;
        public final ForgeConfigSpec.BooleanValue allowOfflineOnFailure;
        public final ForgeConfigSpec.ConfigValue<String> timeoutKickMessage;
        public final ForgeConfigSpec.ConfigValue<String> offlineFallbackMessage;

        // 新增 (Added)
        public final ForgeConfigSpec.ConfigValue<String> offlineShortSubtitle;
        public final ForgeConfigSpec.ConfigValue<String> onlineShortSubtitle;

        // 新增 nomojang 配置 (Added nomojang config)
        public final ForgeConfigSpec.BooleanValue nomojangEnabled;

        // 新增：策略相关 (Added: Strategy related)
        public final ForgeConfigSpec.BooleanValue knownPremiumDenyOffline;
        public final ForgeConfigSpec.BooleanValue allowOfflineForUnknownOnly;
        public final ForgeConfigSpec.BooleanValue recentIpGraceEnabled;
        public final ForgeConfigSpec.IntValue recentIpGraceTtlSeconds;
        public final ForgeConfigSpec.BooleanValue debug;

        Common(ForgeConfigSpec.Builder b) {
            b.push("auth");

            timeoutMs = b.defineInRange("timeoutMs", 10_000L, 1_000L, 600_000L);
            allowOfflineOnTimeout = b.comment("false: Kick on timeout (default) true: Allow offline on timeout").define("allowOfflineOnTimeout", false);
            allowOfflineOnFailure = b.comment("false: Kick on failure true: Allow offline on any auth failure (default)").define("allowOfflineOnFailure", true);

            timeoutKickMessage = b.define("timeoutKickMessage", "Login timeout, account verification not completed");
            offlineFallbackMessage = b.define(
                    "offlineFallbackMessage",
                    "Note: You are currently entering the server in offline mode; if you are a premium account, it may be due to network reasons causing authentication failure, please try logging in again. Continuing to play may result in loss of player data if authentication succeeds later."
            );

            // 默认短、不占屏 (Default short, does not occupy screen)
            offlineShortSubtitle = b.define("offlineShortSubtitle", "Auth Failed: Offline Mode");
            onlineShortSubtitle  = b.define("onlineShortSubtitle",  "Premium Verified");

            // 策略项 (Strategy items)
            knownPremiumDenyOffline   = b.comment("Once a name has been verified as premium, offline entry is prohibited if subsequent authentication fails.")
                    .define("knownPremiumDenyOffline", true);
            allowOfflineForUnknownOnly = b.comment("Only allow offline fallback for new names that have never been verified as premium.")
                    .define("allowOfflineForUnknownOnly", true);
            recentIpGraceEnabled      = b.comment("Enable \"Recent Same IP Success\" grace, temporarily treat as premium if failed within TTL.")
                    .define("recentIpGrace.enabled", true);
            recentIpGraceTtlSeconds   = b.comment("TTL seconds for \"Recent Same IP Success\" grace. Recommended 60~600.")
                    .defineInRange("recentIpGrace.ttlSeconds", 300, 30, 3600);
            debug = b.comment("Enable debug log output").define("debug", false);
            // 新增：跳过 Mojang 会话认证（开启后不再通过 sessionserver 验证） (Added: Skip Mojang session auth (no longer verify via sessionserver when enabled))
            nomojangEnabled = b.comment("When enabled, disables online verification against Mojang session service; names with recent successful premium login from same IP are treated as premium UUID, others are treated as offline.")
                    .define("nomojang.enabled", false);
            b.pop();
        }
    }

    private TrueuuidConfig() {}

}