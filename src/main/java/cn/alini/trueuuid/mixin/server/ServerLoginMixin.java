// java
package cn.alini.trueuuid.mixin.server;

import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.net.NetIds;
import cn.alini.trueuuid.server.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl")
public abstract class ServerLoginMixin {
    @Shadow private GameProfile gameProfile;
    @Shadow private MinecraftServer server;
    @Shadow private Connection connection; // 1.20.1 是字段 (is a field)

    @Shadow public abstract void disconnect(Component reason);
    // 握手状态 (handshake state)
    @Unique private static final AtomicInteger TRUEUUID$NEXT_TX_ID = new AtomicInteger(1);
    @Unique private int trueuuid$txId = 0;
    @Unique private String trueuuid$nonce = null;
    @Unique private long trueuuid$sentAt = 0L;


    // 新增：防止重复处理客户端认证包（同次握手只处理一次）(Added: Prevent duplicate processing of client auth packets (only process once per handshake))
    @Unique private volatile boolean trueuuid$ackHandled = false;

    @Inject(method = "handleHello", at = @At("TAIL"))
    private void trueuuid$afterHello(ServerboundHelloPacket pkt, CallbackInfo ci) {
        if (this.server.usesAuthentication() || this.gameProfile == null) return;

        // 若开启 nomojang，则直接使用本地策略，不向客户端发送会话认证包 (If nomojang is enabled, use local policy directly, do not send session auth packet to client)
        if (TrueuuidConfig.nomojangEnabled()) {
            String name = this.gameProfile.getName();
            String ip;
            if (this.connection.getRemoteAddress() instanceof InetSocketAddress isa) {
                ip = isa.getAddress().getHostAddress();
            } else {
                ip = null;
            }
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] nomojang mode: Skipping Mojang session authentication, Player: " + (name != null ? name : "<unknown>") + ", ip: " + ip);
            }

            // 尝试同 IP 的近期容错命中 -> 视为正版 (Try recent same IP grace hit -> Treat as premium)
            if (TrueuuidConfig.recentIpGraceEnabled() && ip != null) {
                var pOpt = TrueuuidRuntime.IP_GRACE.tryGrace(name, ip, TrueuuidConfig.recentIpGraceTtlSeconds());
                if (pOpt.isPresent()) {
                    UUID premium = pOpt.get();
                    if (premium != null) {
                        if (TrueuuidConfig.debug()) {
                            System.out.println("[TrueUUID] nomojang: Found same IP premium record, treating as premium, uuid=" + premium);
                        }
                        GameProfile newProfile = new GameProfile(premium, name);
                        this.gameProfile = newProfile;
                        // 记录成功（保持注册表/缓存一致） (Record success (keep registry/cache consistent))
                        TrueuuidRuntime.NAME_REGISTRY.recordSuccess(name, premium, ip);
                        TrueuuidRuntime.IP_GRACE.record(name, ip, premium);
                        return; // 直接返回，按正版处理完毕 (Return directly, premium processing complete)
                    }
                }
            }

            // 其余情况：直接按离线处理（不阻止进入） (Other cases: Treat as offline directly (do not block entry))
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] nomojang: No same IP premium record found, allowing offline entry");
            }
            // 不发送自定义认证包，保持默认的离线行为 (Do not send custom auth packet, keep default offline behavior)
            return;
        }


        // 清理 ack 处理标志（新握手重新可处理） (Clear ack handled flag (new handshake can be processed again))
        this.trueuuid$ackHandled = false;

        this.trueuuid$nonce = UUID.randomUUID().toString().replace("-", "");
        this.trueuuid$txId = TRUEUUID$NEXT_TX_ID.getAndIncrement();
        this.trueuuid$sentAt = System.currentTimeMillis();

        if (TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID] handleHello: Starting handshake, Player: " + (this.gameProfile != null ? this.gameProfile.getName() : "<unknown>"));
            System.out.println("[TrueUUID] Handshake nonce: " + this.trueuuid$nonce + ", txId: " + this.trueuuid$txId);
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(this.trueuuid$nonce);

        this.connection.send(new ClientboundCustomQueryPacket(this.trueuuid$txId, NetIds.AUTH, buf));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void trueuuid$onTick(CallbackInfo ci) {
        if (this.trueuuid$txId == 0 || this.trueuuid$sentAt == 0L) return;
        long timeoutMs = TrueuuidConfig.timeoutMs();
        if (timeoutMs <= 0) return;

        long now = System.currentTimeMillis();
        if (now - this.trueuuid$sentAt < timeoutMs) return;

        if (TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID] Handshake timeout, txId: " + this.trueuuid$txId);
        }

        if (TrueuuidConfig.allowOfflineOnTimeout()) {
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] Timeout allows offline entry");
            }
            AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.TIMEOUT);
            reset();
        } else {
            String msg = TrueuuidConfig.timeoutKickMessage();
            Component reason = Component.literal(msg != null ? msg : "Login timeout, account verification not completed");
            sendDisconnectWithReason(reason);
            reset();
        }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void trueuuid$onLoginCustom(ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        if (this.trueuuid$txId == 0) return;
        if (packet.getTransactionId() != this.trueuuid$txId) return;

        String ip;
        if (this.connection.getRemoteAddress() instanceof InetSocketAddress isa) {
            ip = isa.getAddress().getHostAddress();
        } else {
            ip = null;
        }
        if (TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID] Received client auth packet, Player: " + (this.gameProfile != null ? this.gameProfile.getName() : "<unknown>") + ", ip: " + ip);
        }

        FriendlyByteBuf data = packet.getData();
        if (data == null) {
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] Authentication failed, Player: " + (this.gameProfile != null ? this.gameProfile.getName() : "<unknown>") + ", ip: " + ip + ", Reason: Missing data");
            }
            handleAuthFailure(ip, "Missing data");
            reset(); ci.cancel(); return;
        }

        boolean ackOk = false;
        try { ackOk = data.readBoolean(); } catch (Throwable ignored) {}
        if (TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID] Client auth packet ackOk: " + ackOk);
        }
        if (!ackOk) {
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] Authentication failed, Player: " + (this.gameProfile != null ? this.gameProfile.getName() : "<unknown>") + ", ip: " + ip + ", Reason: Client refused");
            }
            handleAuthFailure(ip, "Client refused");
            reset(); ci.cancel(); return;
        }

        // 幂等保护：如果已经处理过本次握手的 ack，则忽略重复包 (Idempotency protection: If ack for this handshake has been processed, ignore duplicate packets)
        if (this.trueuuid$ackHandled) {
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] Duplicate auth packet ignored, txId: " + this.trueuuid$txId);
            }
            ci.cancel();
            return;
        }
        this.trueuuid$ackHandled = true;

        // 关键：使用异步 API，不在主线程阻塞 (Key: Use async API, do not block main thread)
        try {
            // 立即取消原始调用（以免继续执行原有逻辑），但不要 reset()，保留状态直到回调完成
            // (Immediately cancel the original call (to avoid executing original logic), but do not reset(); keep state until callback completes)
            ci.cancel();

            SessionCheck.hasJoinedAsync(this.gameProfile.getName(), this.trueuuid$nonce, ip)
                    .whenComplete((resOpt, throwable) -> {
                        // 始终在主线程处理后续逻辑 (Always process subsequent logic on main thread)
                        server.execute(() -> {
                            try {
                                if (throwable != null) {
                                    if (TrueuuidConfig.debug()) {
                                        System.out.println("[TrueUUID] Exception in auth async callback: " + throwable);
                                    }
                                    handleAuthFailure(ip, "Server exception");
                                    return;
                                }

                                if (resOpt.isEmpty()) {
                                    if (TrueuuidConfig.debug()) {
                                        System.out.println("[TrueUUID] Authentication failed, Player: " + (this.gameProfile != null ? this.gameProfile.getName() : "<unknown>") + ", ip: " + ip + ", Reason: Invalid session");
                                    }
                                    handleAuthFailure(ip, "Invalid session");
                                    return;
                                }

                                var res = resOpt.get();

                                // 成功：记录注册表/近期 IP；替换为正版 UUID + 名称大小写矫正 + 注入皮肤 (Success: Record registry/recent IP; replace with premium UUID + name case correction + inject skin)
                                TrueuuidRuntime.NAME_REGISTRY.recordSuccess(res.name(), res.uuid(), ip);
                                TrueuuidRuntime.IP_GRACE.record(res.name(), ip, res.uuid());

                                GameProfile newProfile = new GameProfile(res.uuid(), res.name());
                                var propMap = newProfile.getProperties();
                                propMap.removeAll("textures");
                                for (var p : res.properties()) {
                                    if (p.signature() != null) {
                                        propMap.put(p.name(), new Property(p.name(), p.value(), p.signature()));
                                    } else {
                                        propMap.put(p.name(), new Property(p.name(), p.value()));
                                    }
                                }
                                this.gameProfile = newProfile;
                                trueuuid$proceedLogin();
                            } catch (Throwable t) {
                                if (TrueuuidConfig.debug()) {
                                    System.out.println("[TrueUUID] Exception during auth async processing: " + t);
                                }
                                handleAuthFailure(ip, "Server exception");
                            } finally {
                                reset();
                            }
                        });
                    });

        } catch (Throwable t) {
            // 若构造异步调用时报错（极少见），则回退为失败处理并重置
            // (If an error occurs when constructing the async call (very rare), fall back to failure handling and reset)
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] Error starting async auth: " + t);
            }
            handleAuthFailure(ip, "Server exception");
            reset();
            this.trueuuid$ackHandled = false;
        }
    }

    @Unique
    private void trueuuid$proceedLogin() {
        try {
            Method method = this.getClass().getDeclaredMethod("m_10055_");
            method.setAccessible(true);
            method.invoke(this);
        } catch (Exception e) {
            if (TrueuuidConfig.debug()) {
                System.out.println("[TrueUUID] 调用失败: " + e);
            }
            disconnect(Component.literal("服务器错误，请稍后重试"));
        }
    }

    @Unique
    private void handleAuthFailure(String ip, String why) {
        String name = this.gameProfile != null ? this.gameProfile.getName() : "<unknown>";
        if (TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID] Invalid session, Player: " + name + ", ip: " + ip + ", Failure reason: " + why);
        }
        AuthDecider.Decision d = AuthDecider.onFailure(name, ip);

        switch (d.kind) {
            case PREMIUM_GRACE -> {
                UUID premium = d.premiumUuid != null ? d.premiumUuid
                        : TrueuuidRuntime.NAME_REGISTRY.getPremiumUuid(name).orElse(null);
                if (premium != null) {
                    this.gameProfile = new GameProfile(premium, name);
                    trueuuid$proceedLogin();
                } else {
                    AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                    trueuuid$proceedLogin();
                }
            }
            case OFFLINE -> {
                if (TrueuuidConfig.debug()) {
                    System.out.println("[TrueUUID] Offline entry");
                }
                AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                trueuuid$proceedLogin();
            }
            case DENY -> {
                String msg = d.denyMessage != null ? d.denyMessage
                        : "Authentication failed, offline entry has been prohibited to protect your premium data. Please try again later.";
                if (TrueuuidConfig.debug()) {
                    System.out.println("[TrueUUID] Authentication denied, Player: " + name + ", ip: " + ip + ", Message: " + msg);
                }
                sendDisconnectWithReason(Component.literal(msg));
            }
        }
    }

    @Unique
    private void sendDisconnectWithReason(Component reason) {
        // 异步断开，避免主线程卡死 (Async disconnect, avoid main thread freeze)
        new Thread(() -> {
            try {
                this.connection.send(new ClientboundLoginDisconnectPacket(reason));
                this.connection.send(new ClientboundDisconnectPacket(reason));
            } catch (Throwable ignored) {}
            this.connection.disconnect(reason);
        }, "TrueUUID-AsyncDisconnect").start();
    }

    @Unique
    private void reset() {
        if (TrueuuidConfig.debug()) {
            System.out.println("[TrueUUID] State reset, txId: " + this.trueuuid$txId);
        }
        this.trueuuid$txId = 0;
        this.trueuuid$nonce = null;
        this.trueuuid$sentAt = 0L;
    }
}
