package com.githublook.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.auth.dto.LoginRequest;
import com.githublook.auth.dto.SessionDto;
import com.githublook.auth.mapper.AppUserMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.common.time.TimeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;
    private final TimeMapper time;

    /**
     * Creates the configured admin account on first boot so the dashboard is not locked out.
     * The password comes from ADMIN_PASSWORD, never from source code.
     */
    @Transactional
    public void ensureAdminUser() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(props.getSecurity().getAdminUsername());
        admin.setDisplayName("管理员");
        admin.setPasswordHash(passwordEncoder.encode(props.getSecurity().getAdminPassword()));
        admin.setRole("ADMIN");
        admin.setCreatedAt(time.now());
        userMapper.insert(admin);
        log.info("admin_user_created username={}", admin.getUsername());
    }

    @Transactional
    public LoginResult login(LoginRequest request) {
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, request.username().trim())
                .last("LIMIT 1"));

        // Same error for unknown user and wrong password: no account enumeration.
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("login_failed username={}", request.username());
            throw new AppException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        user.setLastLoginAt(time.now());
        userMapper.updateById(user);

        String token = jwtService.issue(user.getUsername(), user.getRole());
        String expiresAt = time.now().plus(jwtService.ttl()).format(TS);
        log.info("login_ok username={} role={}", user.getUsername(), user.getRole());
        return new LoginResult(token, new SessionDto(user.getUsername(), user.getDisplayName(),
                user.getRole(), expiresAt));
    }

    public SessionDto session(String username) {
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "账号不存在");
        }
        return new SessionDto(user.getUsername(), user.getDisplayName(), user.getRole(),
                time.now().plus(jwtService.ttl()).format(TS));
    }

    public record LoginResult(String token, SessionDto session) {
    }
}
