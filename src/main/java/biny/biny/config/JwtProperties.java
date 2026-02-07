package biny.biny.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * 签发方
     */
    private String issuer = "yuoj-backend";

    /**
     * Access Token 密钥（HS256，建议 >= 32 字符）
     */
    private String accessSecret;

    /**
     * Refresh Token 密钥（为空则复用 accessSecret）
     */
    private String refreshSecret;

    /**
     * Access Token 有效期（秒）
     */
    private long accessTtlSeconds = 30 * 60;

    /**
     * Refresh Token 有效期（秒）
     */
    private long refreshTtlSeconds = 14L * 24 * 60 * 60;

    /**
     * Access Token 续约窗口（秒）
     */
    private long renewWindowSeconds = 5 * 60;

    /**
     * Refresh Token Cookie 名称
     */
    private String refreshCookieName = "refresh_token";

    /**
     * Refresh Token Cookie Path
     */
    private String refreshCookiePath = "/";

    /**
     * Refresh Token Cookie Domain（可选）
     */
    private String refreshCookieDomain;

    /**
     * Refresh Token Cookie 是否 Secure
     */
    private boolean refreshCookieSecure = false;

    /**
     * Refresh Token Cookie SameSite（Lax/Strict/None）
     */
    private String refreshCookieSameSite = "Lax";
}

