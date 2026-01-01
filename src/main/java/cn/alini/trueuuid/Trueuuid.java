package cn.alini.trueuuid;

import com.mojang.logging.LogUtils;
import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.server.TrueuuidRuntime;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Trueuuid.MODID)
public class Trueuuid {
    public static final String MODID = "trueuuid";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Trueuuid() {
        // 注册并生成 config/trueuuid-common.toml (Register and generate config/trueuuid-common.toml)
        TrueuuidConfig.register();

        // 初始化运行时单例（注册表、最近 IP 容错缓存等） (Initialize runtime singleton (registry, recent IP grace cache, etc.))
        TrueuuidRuntime.init();

        // ===== MoJang网络连通性测试 (Mojang Network Connectivity Test)=====
        // 若开启 nomojang，则跳过启动时的 Mojang 网络连通性检测 (If nomojang is enabled, skip Mojang network connectivity check at startup)
        if (TrueuuidConfig.nomojangEnabled()) {
            LOGGER.info("nomojang enabled, skipping Mojang session server connectivity check");
        } else {
            // ===== MoJang网络连通性测试 (Mojang Network Connectivity Test )=====
            try {
                String testUrl = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=Mojang&serverId=test";
                java.net.URL url = new java.net.URL(testUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000); // 3秒超时 (3 seconds timeout)
                conn.setReadTimeout(3000);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200 || responseCode == 204 || responseCode == 403) {
                    LOGGER.info("Successfully connected to Mojang session server (sessionserver.mojang.com), response code: {}", responseCode);
                } else {
                    LOGGER.warn("Mojang session server response exception, response code: {}", responseCode);
                }
            } catch (Exception e) {
                LOGGER.error("Unable to connect to Mojang session server (sessionserver.mojang.com), please check network connection or firewall settings.", e);
            }
        }

        LOGGER.info("TrueUUID loaded");
    }
}