package biny.biny.security;

import biny.biny.common.ErrorCode;
import biny.biny.config.JwtProperties;
import biny.biny.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * JWT Token 相关能力（签发 / 校验 / 续约 / Refresh 旋转 / 撤销）
 */
@Service
public class JwtTokenService {

    private static final String CLAIM_TOKEN_VERSION = "ver";

    private static final String KEY_VERSION_PREFIX = "jwt:ver:";

    private static final String KEY_REFRESH_JTI_PREFIX = "jwt:rt:";

    private final JwtProperties jwtProperties;

    private final StringRedisTemplate stringRedisTemplate;

    private final SecretKey accessKey;

    private final SecretKey refreshKey;

    public JwtTokenService(JwtProperties jwtProperties, StringRedisTemplate stringRedisTemplate) {
        this.jwtProperties = jwtProperties;
        this.stringRedisTemplate = stringRedisTemplate;
        this.accessKey = buildKeyOrNull(jwtProperties.getAccessSecret());
        String refreshSecret = StringUtils.defaultIfBlank(jwtProperties.getRefreshSecret(), jwtProperties.getAccessSecret());
        this.refreshKey = buildKeyOrNull(refreshSecret);
    }

    public long getAccessTtlSeconds() {
        return jwtProperties.getAccessTtlSeconds();
    }

    public TokenPair issueTokens(Long userId) {
        requireSignKeyConfigured();
        int ver = getOrInitTokenVersion(userId);
        String accessToken = issueAccessToken(userId, ver);
        String refreshToken = issueRefreshToken(userId, ver);
        return new TokenPair(accessToken, jwtProperties.getAccessTtlSeconds(), refreshToken);
    }

    public TokenPair refresh(String refreshToken) {
        requireSignKeyConfigured();
        RefreshTokenPayload payload = parseRefreshToken(refreshToken);
        Long userId = payload.getUserId();
        int tokenVersion = payload.getTokenVersion();

        int currentVersion = getCurrentTokenVersion(userId);
        if (tokenVersion != currentVersion) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        String jtiKey = refreshJtiKey(payload.getJti());
        String expected = userId + ":" + tokenVersion;
        String actual = stringRedisTemplate.opsForValue().get(jtiKey);
        if (!expected.equals(actual)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        // 旋转 refresh token：旧的立即失效
        stringRedisTemplate.delete(jtiKey);

        String newAccessToken = issueAccessToken(userId, tokenVersion);
        String newRefreshToken = issueRefreshToken(userId, tokenVersion);
        return new TokenPair(newAccessToken, jwtProperties.getAccessTtlSeconds(), newRefreshToken);
    }

    public String renewAccessToken(Long userId, int tokenVersion) {
        requireSignKeyConfigured();
        return issueAccessToken(userId, tokenVersion);
    }

    public AccessTokenPayload parseAccessToken(String token) {
        Jws<Claims> jws = parse(token, accessKey);
        Claims claims = jws.getBody();
        Long userId = parseUserId(claims);
        int tokenVersion = parseTokenVersion(claims);
        long expEpochSeconds = claims.getExpiration().toInstant().getEpochSecond();
        return new AccessTokenPayload(userId, tokenVersion, expEpochSeconds);
    }

    public int getCurrentTokenVersion(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(versionKey(userId));
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "jwt token version invalid");
        }
    }

    public int getOrInitTokenVersion(Long userId) {
        String key = versionKey(userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.isBlank(value)) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "jwt token version invalid");
            }
        }
        stringRedisTemplate.opsForValue().setIfAbsent(key, "0");
        return 0;
    }

    public int bumpTokenVersion(Long userId) {
        Long newValue = stringRedisTemplate.opsForValue().increment(versionKey(userId));
        return newValue == null ? 0 : newValue.intValue();
    }

    private String issueAccessToken(Long userId, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())
                .setSubject(String.valueOf(userId))
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(jwtProperties.getAccessTtlSeconds())))
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .signWith(accessKey, SignatureAlgorithm.HS256)
                .compact();
    }

    private String issueRefreshToken(Long userId, int tokenVersion) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(
                refreshJtiKey(jti),
                userId + ":" + tokenVersion,
                jwtProperties.getRefreshTtlSeconds(),
                TimeUnit.SECONDS
        );
        return Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())
                .setSubject(String.valueOf(userId))
                .setId(jti)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(jwtProperties.getRefreshTtlSeconds())))
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .signWith(refreshKey, SignatureAlgorithm.HS256)
                .compact();
    }

    private RefreshTokenPayload parseRefreshToken(String token) {
        Jws<Claims> jws = parse(token, refreshKey);
        Claims claims = jws.getBody();
        Long userId = parseUserId(claims);
        int tokenVersion = parseTokenVersion(claims);
        String jti = claims.getId();
        if (StringUtils.isBlank(jti)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return new RefreshTokenPayload(userId, tokenVersion, jti);
    }

    private Jws<Claims> parse(String token, SecretKey key) {
        if (StringUtils.isBlank(token)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (key == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(jwtProperties.getIssuer())
                    .build()
                    .parseClaimsJws(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }

    private static Long parseUserId(Claims claims) {
        String sub = claims.getSubject();
        if (StringUtils.isBlank(sub)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        try {
            return Long.valueOf(sub);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }

    private static int parseTokenVersion(Claims claims) {
        Object verObj = claims.get(CLAIM_TOKEN_VERSION);
        if (verObj == null) {
            return 0;
        }
        if (verObj instanceof Number) {
            return ((Number) verObj).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(verObj));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }

    private static String versionKey(Long userId) {
        return KEY_VERSION_PREFIX + userId;
    }

    private static String refreshJtiKey(String jti) {
        return KEY_REFRESH_JTI_PREFIX + jti;
    }

    private void requireSignKeyConfigured() {
        if (accessKey == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "JWT access-secret 未配置");
        }
        if (refreshKey == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "JWT refresh-secret 未配置");
        }
    }

    private static SecretKey buildKeyOrNull(String secret) {
        if (StringUtils.isBlank(secret)) {
            return null;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt secret length must be >= 32 bytes");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    @Getter
    public static class TokenPair {
        private final String accessToken;
        private final long accessExpiresInSeconds;
        private final String refreshToken;

        public TokenPair(String accessToken, long accessExpiresInSeconds, String refreshToken) {
            this.accessToken = accessToken;
            this.accessExpiresInSeconds = accessExpiresInSeconds;
            this.refreshToken = refreshToken;
        }
    }

    @Getter
    public static class AccessTokenPayload {
        private final Long userId;
        private final int tokenVersion;
        private final long expEpochSeconds;

        public AccessTokenPayload(Long userId, int tokenVersion, long expEpochSeconds) {
            this.userId = userId;
            this.tokenVersion = tokenVersion;
            this.expEpochSeconds = expEpochSeconds;
        }
    }

    @Getter
    private static class RefreshTokenPayload {
        private final Long userId;
        private final int tokenVersion;
        private final String jti;

        private RefreshTokenPayload(Long userId, int tokenVersion, String jti) {
            this.userId = userId;
            this.tokenVersion = tokenVersion;
            this.jti = jti;
        }
    }
}
