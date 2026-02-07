package biny.biny.interceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import biny.biny.context.UserContextHolder;
import biny.biny.model.entity.User;
import biny.biny.model.enums.UserRoleEnum;
import biny.biny.security.JwtTokenService;
import biny.biny.config.JwtProperties;
import biny.biny.service.UserService;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthInterceptorTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void preHandle_withoutAuthorization_shouldPass() {
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        UserService userService = mock(UserService.class);
        JwtProperties props = new JwtProperties();
        props.setRenewWindowSeconds(300);

        JwtAuthInterceptor interceptor = new JwtAuthInterceptor(jwtTokenService, userService, props);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserContextHolder.set(new User());
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserContextHolder.get());
    }

    @Test
    void preHandle_withValidToken_shouldSetContext_andRenew() {
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        UserService userService = mock(UserService.class);
        JwtProperties props = new JwtProperties();
        props.setRenewWindowSeconds(200);

        JwtAuthInterceptor interceptor = new JwtAuthInterceptor(jwtTokenService, userService, props);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("Authorization", "Bearer test_token");
        long exp = Instant.now().getEpochSecond() + 100;
        when(jwtTokenService.parseAccessToken("test_token"))
                .thenReturn(new JwtTokenService.AccessTokenPayload(1L, 0, exp));
        when(jwtTokenService.getCurrentTokenVersion(1L)).thenReturn(0);
        User user = new User();
        user.setId(1L);
        user.setUserRole(UserRoleEnum.USER.getValue());
        when(userService.getById(1L)).thenReturn(user);

        when(jwtTokenService.renewAccessToken(1L, 0)).thenReturn("new_token");
        when(jwtTokenService.getAccessTtlSeconds()).thenReturn(1800L);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNotNull(UserContextHolder.get());
        assertEquals("new_token", response.getHeader("X-New-Access-Token"));
        assertEquals("1800", response.getHeader("X-New-Access-Token-Expires-In"));

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserContextHolder.get());
    }
}
