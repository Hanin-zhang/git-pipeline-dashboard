package com.githublook.auth;

import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoginRateLimiter · 登录限流（5 分钟 10 次）")
class LoginRateLimiterTest {

    private final LoginRateLimiter limiter = new LoginRateLimiter();

    @Test
    @DisplayName("前 10 次失败不拦截，第 11 次直接 429")
    void blocksAfterTenFailures() {
        IntStream.range(0, 10).forEach(i -> {
            limiter.check("1.2.3.4");
            limiter.recordFailure("1.2.3.4");
        });

        assertThatThrownBy(() -> limiter.check("1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("9 次失败仍可继续尝试，避免正常用户被误伤")
    void allowsNineFailures() {
        IntStream.range(0, 9).forEach(i -> limiter.recordFailure("1.2.3.4"));

        assertThatCode(() -> limiter.check("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("限流按客户端隔离，A 被限流不影响 B")
    void isolatesClients() {
        IntStream.range(0, 10).forEach(i -> limiter.recordFailure("1.1.1.1"));

        assertThatThrownBy(() -> limiter.check("1.1.1.1")).isInstanceOf(AppException.class);
        assertThatCode(() -> limiter.check("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("登录成功后重置计数")
    void resetClearsFailures() {
        IntStream.range(0, 10).forEach(i -> limiter.recordFailure("1.2.3.4"));
        assertThatThrownBy(() -> limiter.check("1.2.3.4")).isInstanceOf(AppException.class);

        limiter.reset("1.2.3.4");

        assertThatCode(() -> limiter.check("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("从未失败过的客户端不会被限流")
    void unknownClientIsNotLimited() {
        assertThatCode(() -> limiter.check("9.9.9.9")).doesNotThrowAnyException();
        assertThat(limiter).isNotNull();
    }
}
