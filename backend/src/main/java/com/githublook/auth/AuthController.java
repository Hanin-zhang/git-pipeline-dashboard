package com.githublook.auth;

import com.githublook.auth.dto.LoginRequest;
import com.githublook.auth.dto.SessionDto;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppProperties props;
    private final LoginRateLimiter rateLimiter;

    @PostMapping("/login")
    public SessionDto login(@Valid @RequestBody LoginRequest request,
                            HttpServletRequest httpRequest,
                            HttpServletResponse httpResponse) {
        String client = clientKey(httpRequest);
        rateLimiter.check(client);
        try {
            AuthService.LoginResult result = authService.login(request);
            rateLimiter.reset(client);
            httpResponse.addHeader(HttpHeaders.SET_COOKIE,
                    cookie(result.token(), props.getSecurity().getAccessTokenTtl().getSeconds()).toString());
            return result.session();
        } catch (AppException e) {
            if (e.getCode() == ErrorCode.UNAUTHORIZED) {
                rateLimiter.recordFailure(client);
            }
            throw e;
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletResponse httpResponse) {
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    @GetMapping("/me")
    public SessionDto me(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return authService.session(authentication.getName());
    }

    private ResponseCookie cookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(props.getSecurity().getCookieName(), token)
                .httpOnly(true)
                .secure(props.getSecurity().isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
