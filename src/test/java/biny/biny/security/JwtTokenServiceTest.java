package biny.biny.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import biny.biny.common.ErrorCode;
import biny.biny.config.JwtProperties;
import biny.biny.exception.BusinessException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtTokenServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtTokenService jwtTokenService;

    private final Map<String, String> redis = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setIssuer("test-issuer");
        props.setAccessSecret("01234567890123456789012345678901"); // 32 bytes
        props.setRefreshSecret("abcdefghijklmnopqrstuvwxyz012345"); // 32 bytes
        props.setAccessTtlSeconds(60);
        props.setRefreshTtlSeconds(60);
        props.setRenewWindowSeconds(30);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(inv -> redis.get(inv.getArgument(0)));
        when(valueOperations.setIfAbsent(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            return redis.putIfAbsent(key, value) == null;
        });
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            redis.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(valueOperations.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long cur = 0;
            String value = redis.get(key);
            if (value != null) {
                cur = Long.parseLong(value);
            }
            long next = cur + 1;
            redis.put(key, String.valueOf(next));
            return next;
        });
        when(stringRedisTemplate.delete(anyString())).thenAnswer(inv -> redis.remove(inv.getArgument(0)) != null);

        jwtTokenService = new JwtTokenService(props, stringRedisTemplate);
    }

    @Test
    void issueTokens_and_parseAccessToken() {
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokens(1L);
        assertNotNull(tokenPair);
        assertNotNull(tokenPair.getAccessToken());
        assertNotNull(tokenPair.getRefreshToken());
        assertEquals(60, tokenPair.getAccessExpiresInSeconds());

        JwtTokenService.AccessTokenPayload payload = jwtTokenService.parseAccessToken(tokenPair.getAccessToken());
        assertEquals(1L, payload.getUserId());
        assertEquals(0, payload.getTokenVersion());
        assertTrue(payload.getExpEpochSeconds() > 0);
    }

    @Test
    void refresh_shouldRotateRefreshToken_andInvalidateOld() {
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokens(1L);
        JwtTokenService.TokenPair refreshed = jwtTokenService.refresh(tokenPair.getRefreshToken());
        assertNotNull(refreshed.getAccessToken());
        assertNotNull(refreshed.getRefreshToken());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> jwtTokenService.refresh(tokenPair.getRefreshToken()));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }

    @Test
    void bumpTokenVersion_shouldInvalidateOldRefreshToken() {
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokens(1L);
        jwtTokenService.bumpTokenVersion(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> jwtTokenService.refresh(tokenPair.getRefreshToken()));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
    }
}
