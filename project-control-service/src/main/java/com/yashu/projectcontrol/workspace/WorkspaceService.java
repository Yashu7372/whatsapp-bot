package com.yashu.projectcontrol.workspace;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository repository;

    public WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WorkspaceView create(String code, String name) {
        String normalizedCode = normalizeCode(code);
        if (repository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace code already exists: " + normalizedCode);
        }

        Workspace workspace = repository.save(Workspace.create(normalizedCode, name.trim()));
        return toView(workspace);
    }

    @Transactional(readOnly = true)
    public WorkspaceView get(UUID id) {
        return toView(requireEntity(id));
    }

    @Transactional(readOnly = true)
    public void requireExists(UUID id) {
        requireEntity(id);
    }

    private Workspace requireEntity(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + id));
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static WorkspaceView toView(Workspace workspace) {
        return new WorkspaceView(
                workspace.getId(),
                workspace.getCode(),
                workspace.getName(),
                workspace.getStatus().name());
    }

    public record WorkspaceView(UUID id, String code, String name, String status) {
    }
}
