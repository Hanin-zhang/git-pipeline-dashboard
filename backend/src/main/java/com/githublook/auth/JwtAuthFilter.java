package com.githublook.auth;

import com.githublook.common.config.AppProperties;
import com.githublook.settings.SettingsGateService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 无状态认证。令牌优先从 httpOnly Cookie 读取（浏览器，含无法自定义头的 EventSource），
 * 其次从 Authorization 头读取（curl / CLI / 测试）。
 *
 * <p>这里还会额外检查「设置页解锁」Cookie：解锁有效时，本次请求会多出
 * ROLE_SETTINGS_ADMIN 权限，用于访问 /api/settings/** 与 /api/repos/**。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppProperties props;
    private final SettingsGateService settingsGate;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            resolveToken(request).flatMap(jwtService::parse).ifPresent(claims -> authenticate(request, claims));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        String username = claims.getSubject();
        String role = claims.get("role", String.class);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role)));

        String settingsToken = resolveCookie(request, props.getSecurity().getSettingsCookieName());
        if (settingsGate.isValid(settingsToken)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SETTINGS_ADMIN"));
        }

        var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Optional<String> resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring(7).trim());
        }
        return Optional.ofNullable(resolveCookie(request, props.getSecurity().getCookieName()));
    }

    private String resolveCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || name == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
