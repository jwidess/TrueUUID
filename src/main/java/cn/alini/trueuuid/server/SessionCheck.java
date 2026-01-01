// java
package cn.alini.trueuuid.server;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 服务端调用 hasJoined 校验正版并获取最终 UUID 与皮肤属性
 * (Server calls hasJoined to verify premium status and get final UUID and skin properties)
 */
public final class SessionCheck {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    public record Property(String name, String value, String signature) {}

    public record HasJoinedResult(UUID uuid, String name, List<Property> properties) {}

    private static class HasJoinedJson {
        String id; // 无连字符的 UUID (UUID without hyphens)
        String name;
        List<Prop> properties;
    }
    private static class Prop {
        String name;
        String value;
        @SerializedName("signature")
        String sig;
    }

    /**
     * 异步版本：不阻塞调用线程，返回 CompletableFuture\<Optional\<HasJoinedResult\>\>
     * (Async version: Does not block calling thread, returns CompletableFuture<Optional<HasJoinedResult>>)
     */
    public static CompletableFuture<Optional<HasJoinedResult>> hasJoinedAsync(String username, String serverId, String ip) {
        String url = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
                + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);

        if (cn.alini.trueuuid.config.TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID][DEBUG] Requesting Mojang verification interface: " + url);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (cn.alini.trueuuid.config.TrueuuidConfig.debug()) {
                        System.out.println("[TrueUUID][DEBUG] Mojang response status code: " + resp.statusCode());
                        System.out.println("[TrueUUID][DEBUG] Mojang response content: " + resp.body());
                    }

                    if (resp.statusCode() != 200) {
                        if (cn.alini.trueuuid.config.TrueuuidConfig.debug()) {
                            System.out.println("[TrueUUID][DEBUG] Verification failed, status code not 200, returning empty");
                        }
                        return Optional.<HasJoinedResult>empty();
                    }

                    HasJoinedJson dto = GSON.fromJson(resp.body(), HasJoinedJson.class);
                    if (dto == null || dto.id == null) {
                        if (cn.alini.trueuuid.config.TrueuuidConfig.debug()) {
                            System.out.println("[TrueUUID][DEBUG] Failed to parse JSON or UUID not obtained, returning empty");
                        }
                        return Optional.<HasJoinedResult>empty();
                    }

                    UUID uuid = UUID.fromString(dto.id.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                            "$1-$2-$3-$4-$5"));

                    if (cn.alini.trueuuid.config.TrueuuidConfig.debug()) {
                        System.out.println("[TrueUUID][DEBUG] Verification successful, UUID: " + uuid + ", Player Name: " + dto.name);
                    }

                    List<Property> props = dto.properties == null ? List.of() :
                            dto.properties.stream()
                                    .map(p -> new Property(p.name, p.value, p.sig))
                                    .toList();

                    return Optional.of(new HasJoinedResult(uuid, dto.name, props));
                })
                .exceptionally(ex -> {
                    if (cn.alini.trueuuid.config.TrueuuidConfig.debug()) {
                        System.out.println("[TrueUUID][DEBUG] Exception occurred during communication with Mojang or parsing: " + ex);
                    }
                    return Optional.empty();
                });
    }

    // 保留同步方法（若需要）或移除 (Keep synchronous method (if needed) or remove)
    public static Optional<HasJoinedResult> hasJoined(String username, String serverId, String ip) throws Exception {
        // 保留原同步实现（或内部调用 hasJoinedAsync().get()，视需要） (Keep original synchronous implementation (or call hasJoinedAsync().get() internally, as needed))
        throw new UnsupportedOperationException("Synchronous hasJoined is deprecated, please use hasJoinedAsync");
    }

    private SessionCheck() {}
}
