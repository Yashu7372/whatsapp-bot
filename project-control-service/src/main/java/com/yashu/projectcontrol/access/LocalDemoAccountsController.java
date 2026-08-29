package com.yashu.projectcontrol.access;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Profile("local")
@RequestMapping("/api/local/demo-accounts")
public class LocalDemoAccountsController {

    private final IdentityAccessRepository repository;

    public LocalDemoAccountsController(IdentityAccessRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<DemoAccountView> accounts() {
        return LocalDemoAccountCatalog.ACCOUNTS.stream()
                .map(spec -> repository.findUserByEmail(spec.email())
                        .map(row -> new DemoAccountView(
                                spec.key(), row.id(), row.email(), row.displayName()))
                        .orElseThrow())
                .toList();
    }

    public record DemoAccountView(String key, UUID id, String email, String displayName) {}
}
