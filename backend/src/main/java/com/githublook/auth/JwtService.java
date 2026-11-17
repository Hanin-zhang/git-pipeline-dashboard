package com.githublook.auth;

import com.githublook.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * HS256 tokens. The JWT lives in an httpOnly cookie, never in localStorage and never in a
 * URL query parameter — that keeps it out of reach of XSS and out of proxy/browser logs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties props;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(props.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String username, String role) {
        Duration ttl = props.getSecurity().getAccessTokenTtl();
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key())
                .compact();
    }

    /**
     * 受限作用域的短期令牌（用于「设置页解锁」这类二次验证），
     * 与登录令牌分开存放、分开校验，互不影响。
     */
    public String issueScoped(String subject, String scope, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("scope", scope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key())
                .compact();
    }

    /** 校验令牌并返回指定 claim，scope 不匹配时返回 empty。 */
    public Optional<Claims> parseWithScope(String token, String scope) {
        return parse(token).filter(claims -> scope.equals(claims.get("scope", String.class)));
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("jwt_rejected reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Duration ttl() {
        return props.getSecurity().getAccessTokenTtl();
    }
}
