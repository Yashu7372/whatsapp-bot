package com.yashu.projectcontrol.access;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class LocalDemoAuthenticationBootstrap implements ApplicationRunner {

    private final IdentityService identityService;
    private final PasswordEncoder passwordEncoder;

    LocalDemoAuthenticationBootstrap(IdentityService identityService, PasswordEncoder passwordEncoder) {
        this.identityService = identityService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var account : LocalDemoAccountCatalog.ACCOUNTS) {
            identityService.ensureCredentialUser(
                    account.subject(), account.email(), account.displayName(),
                    passwordEncoder.encode(LocalDemoAccountCatalog.PASSWORD));
        }
    }
}
