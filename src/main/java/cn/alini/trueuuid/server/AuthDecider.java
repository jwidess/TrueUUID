package cn.alini.trueuuid.server;

import cn.alini.trueuuid.config.TrueuuidConfig;

import java.util.Optional;
import java.util.UUID;

public final class AuthDecider {

    public static class Decision {
        public enum Kind { PREMIUM_GRACE, OFFLINE, DENY }
        public Kind kind;
        public UUID premiumUuid; // PREMIUM_GRACE 时填 (Fill when PREMIUM_GRACE)
        public String denyMessage;
    }

    public static Decision onFailure(String name, String ip) {
        Decision d = new Decision();

        boolean known = TrueuuidRuntime.NAME_REGISTRY.isKnownPremiumName(name);

        // 1) 已验证过正版的名字：禁止离线回落 (Names already verified as premium: Deny offline fallback)
        if (known && TrueuuidConfig.knownPremiumDenyOffline()) {
            d.kind = Decision.Kind.DENY;
            d.denyMessage = "This name is already bound to a premium UUID, offline mode entry is not allowed when authentication fails. Please check your network and try again.";
            return d;
        }

        // 2) 近期同 IP 成功容错：临时按正版处理 (Recent same IP success grace: Temporarily treat as premium)
        if (TrueuuidConfig.recentIpGraceEnabled()) {
            Optional<UUID> p = TrueuuidRuntime.IP_GRACE.tryGrace(name, ip, TrueuuidConfig.recentIpGraceTtlSeconds());
            if (p.isPresent()) {
                d.kind = Decision.Kind.PREMIUM_GRACE;
                d.premiumUuid = p.get();
                return d;
            }
        }

        // 3) 未知名字：可允许离线兜底 (Unknown names: Allow offline fallback)
        if (TrueuuidConfig.allowOfflineForUnknownOnly() && !known) {
            d.kind = Decision.Kind.OFFLINE;
            return d;
        }

        // 4) 否则拒绝 (Otherwise deny)
        d.kind = Decision.Kind.DENY;
        d.denyMessage = "Authentication failed, offline entry has been prohibited to protect your premium data. Please try again later.";
        return d;
    }

    private AuthDecider() {}

}