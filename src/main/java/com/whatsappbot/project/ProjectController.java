package com.whatsappbot.project;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final DocumentNumberService numberService;

    // ── Projects ───────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal Claims claims,
                                                   @RequestBody ProjectService.CreateProjectRequest req) {
        return ResponseEntity.ok(toResponse(projectService.create(tenantId(claims), req)));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(@AuthenticationPrincipal Claims claims,
                                                       @RequestParam(required = false) String status) {
        return ResponseEntity.ok(projectService.list(tenantId(claims), status)
                .stream().map(ProjectController::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> get(@AuthenticationPrincipal Claims claims,
                                                @PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(projectService.get(tenantId(claims), id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@AuthenticationPrincipal Claims claims,
                                                   @PathVariable UUID id,
                                                   @RequestBody ProjectService.UpdateProjectRequest req) {
        return ResponseEntity.ok(toResponse(projectService.update(tenantId(claims), id, req)));
    }

    // ── Participants ───────────────────────────────────────────────────────

    @PostMapping("/{id}/participants")
    public ResponseEntity<ProjectService.ParticipantView> addParticipant(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody ProjectService.AddParticipantRequest req) {
        return ResponseEntity.ok(projectService.addParticipant(tenantId(claims), id, req));
    }

    // Participants are mapped inside the service transaction — the organization is LAZY.
    @GetMapping("/{id}/participants")
    public ResponseEntity<List<ProjectService.ParticipantView>> listParticipants(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(projectService.listParticipants(tenantId(claims), id, activeOnly));
    }

    /** The companies engaged beneath a participant — a contractor's subcontractors. */
    @GetMapping("/participants/{participantId}/engaged")
    public ResponseEntity<List<ProjectService.ParticipantView>> listEngaged(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID participantId) {
        return ResponseEntity.ok(projectService.listEngagedBy(tenantId(claims), participantId));
    }

    @DeleteMapping("/participants/{participantId}")
    public ResponseEntity<Void> removeParticipant(@AuthenticationPrincipal Claims claims,
                                                   @PathVariable UUID participantId) {
        projectService.removeParticipant(tenantId(claims), participantId);
        return ResponseEntity.noContent().build();
    }

    // ── Document numbering ─────────────────────────────────────────────────

    @PostMapping("/{id}/number-series")
    public ResponseEntity<SeriesResponse> defineSeries(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody DocumentNumberService.DefineSeriesRequest req) {
        return ResponseEntity.ok(toResponse(numberService.defineSeries(tenantId(claims), id, req)));
    }

    @GetMapping("/{id}/number-series")
    public ResponseEntity<List<SeriesResponse>> listSeries(@AuthenticationPrincipal Claims claims,
                                                            @PathVariable UUID id) {
        return ResponseEntity.ok(numberService.listSeries(tenantId(claims), id)
                .stream().map(ProjectController::toResponse).toList());
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static ProjectResponse toResponse(ProjectEntity p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getProjectCode(), p.getDescription(),
                p.getContractValue(), p.getCurrency(), p.getRetentionPercent(), p.getStatus(),
                p.getStartDate(), p.getEndDate(), p.getCreatedAt(), p.getUpdatedAt());
    }

    private static SeriesResponse toResponse(DocumentNumberSeriesEntity s) {
        return new SeriesResponse(s.getId(), s.getProjectId(), s.getDocType(), s.getPrefix(),
                s.getNextNumber(), s.getPadding(), s.getResponseDays());
    }

    public record ProjectResponse(UUID id, String name, String projectCode, String description,
                                   BigDecimal contractValue, String currency,
                                   BigDecimal retentionPercent, String status,
                                   LocalDate startDate, LocalDate endDate,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record SeriesResponse(UUID id, UUID projectId, String docType, String prefix,
                                  int nextNumber, int padding, Integer responseDays) {}
}
