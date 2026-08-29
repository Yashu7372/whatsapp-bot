package com.yashu.projectcontrol.access;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class ProjectControlUserDetailsService implements UserDetailsService {

    private final IdentityAccessRepository repository;

    ProjectControlUserDetailsService(IdentityAccessRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var row = repository.findUserByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        if (row.passwordHash() == null || row.passwordHash().isBlank()) {
            throw new UsernameNotFoundException("User has no local credential");
        }
        return new ProjectControlPrincipal(
                row.id(), row.email(), row.displayName(), row.passwordHash(), "ACTIVE".equals(row.status()));
    }
}
