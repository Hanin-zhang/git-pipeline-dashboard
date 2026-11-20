package com.githublook.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates the first admin account before anything else needs it. */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        authService.ensureAdminUser();
    }
}
