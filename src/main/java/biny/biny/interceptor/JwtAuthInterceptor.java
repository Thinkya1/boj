package biny.biny.interceptor;

import biny.biny.common.ErrorCode;
import biny.biny.config.JwtProperties;
import biny.biny.context.UserContextHolder;
import biny.biny.exception.BusinessException;
import biny.biny.model.entity.User;
import biny.biny.model.enums.UserRoleEnum;
import biny.biny.security.JwtTokenService;
import biny.biny.service.UserService;
import java.time.Duration;
import java.time.Instant;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器：校验 Access Token，写入 ThreadLocal，并支持续约
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String AUTH_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String HEADER_NEW_ACCESS_TOKEN = "X-New-Access-Token";

    private static final String HEADER_NEW_ACCESS_TOKEN_EXPIRES_IN = "X-New-Access-Token-Expires-In";

    private final JwtTokenService jwtTokenService;

    private final UserService userService;

    private final JwtProperties jwtProperties;

    public JwtAuthInterceptor(JwtTokenService jwtTokenService, UserService userService, JwtProperties jwtProperties) {
        this.jwtTokenService = jwtTokenService;
        this.userService = userService;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContextHolder.clear();
        String token = null;
        String authHeader = request.getHeader(AUTH_HEADER);
        if (StringUtils.isNotBlank(authHeader)) {
            String trimmed = authHeader.trim();
            if (StringUtils.startsWithIgnoreCase(trimmed, BEARER_PREFIX)) {
                token = trimmed.substring(BEARER_PREFIX.length()).trim();
            } else if (!trimmed.contains(" ")) {
                // 兼容：前端直接把 token 放到 Authorization，不带 Bearer
                token = trimmed;
            }
        }
        if (StringUtils.isBlank(token) && StringUtils.isNotBlank(jwtProperties.getAccessCookieName())) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (jwtProperties.getAccessCookieName().equals(cookie.getName())) {
                        token = cookie.getValue();
                        break;
                    }
                }
            }
        }
        if (StringUtils.isBlank(token)) {
            return true;
        }

        JwtTokenService.AccessTokenPayload payload = jwtTokenService.parseAccessToken(token);
        Long userId = payload.getUserId();
        int tokenVersion = payload.getTokenVersion();
        int currentVersion = jwtTokenService.getCurrentTokenVersion(userId);
        if (tokenVersion != currentVersion) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        User user = userService.getById(userId);
        if (user == null || user.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (UserRoleEnum.BAN.getValue().equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "该用户已被封禁，禁止访问");
        }
        UserContextHolder.set(user);

        // access token 临期续约（返回新 token 给前端替换保存）
        long nowSec = Instant.now().getEpochSecond();
        long remainSeconds = payload.getExpEpochSeconds() - nowSec;
        if (remainSeconds > 0 && remainSeconds <= jwtProperties.getRenewWindowSeconds()) {
            String newToken = jwtTokenService.renewAccessToken(userId, tokenVersion);
            response.setHeader(HEADER_NEW_ACCESS_TOKEN, newToken);
            response.setHeader(HEADER_NEW_ACCESS_TOKEN_EXPIRES_IN, String.valueOf(jwtTokenService.getAccessTtlSeconds()));
            if (StringUtils.isNotBlank(jwtProperties.getAccessCookieName())) {
                ResponseCookie.ResponseCookieBuilder accessCookieBuilder = ResponseCookie.from(jwtProperties.getAccessCookieName(), newToken)
                        .httpOnly(true)
                        .secure(jwtProperties.isRefreshCookieSecure())
                        .path(jwtProperties.getRefreshCookiePath())
                        .sameSite(jwtProperties.getRefreshCookieSameSite())
                        .maxAge(Duration.ofSeconds(jwtTokenService.getAccessTtlSeconds()));
                if (StringUtils.isNotBlank(jwtProperties.getRefreshCookieDomain())) {
                    accessCookieBuilder.domain(jwtProperties.getRefreshCookieDomain());
                }
                response.addHeader(HttpHeaders.SET_COOKIE, accessCookieBuilder.build().toString());
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContextHolder.clear();
    }
}
