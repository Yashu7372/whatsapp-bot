package com.whatsappbot.approval;

import com.whatsappbot.content.ContentIdeaRepository;
import com.whatsappbot.content.ContentStatus;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalService {

    private final ApprovalTaskRepository approvalTaskRepository;
    private final ContentIdeaRepository contentIdeaRepository;

    @Transactional
    public ApprovalTaskEntity createTask(TenantEntity tenant, UUID contentIdeaId) {
        ApprovalTaskEntity task = ApprovalTaskEntity.create(tenant, contentIdeaId);
        ApprovalTaskEntity saved = approvalTaskRepository.save(task);
        log.info("Created approval task id={} contentIdeaId={} tenant={}", saved.getId(), contentIdeaId, tenant.getId());
        return saved;
    }

    @Transactional
    public ApprovalTaskEntity approve(UUID taskId, String reviewedBy, String note) {
        ApprovalTaskEntity task = findTaskById(taskId);
        task.setStatus(ApprovalStatus.APPROVED);
        task.setReviewedBy(reviewedBy);
        task.setReviewerNote(note);
        task.setReviewedAt(LocalDateTime.now());
        ApprovalTaskEntity saved = approvalTaskRepository.save(task);
        updateContentIdeaStatus(task.getContentIdeaId(), ContentStatus.APPROVED);
        return saved;
    }

    @Transactional
    public ApprovalTaskEntity reject(UUID taskId, String reviewedBy, String note) {
        ApprovalTaskEntity task = findTaskById(taskId);
        task.setStatus(ApprovalStatus.REJECTED);
        task.setReviewedBy(reviewedBy);
        task.setReviewerNote(note);
        task.setReviewedAt(LocalDateTime.now());
        ApprovalTaskEntity saved = approvalTaskRepository.save(task);
        updateContentIdeaStatus(task.getContentIdeaId(), ContentStatus.REJECTED);
        return saved;
    }

    @Transactional
    public ApprovalTaskEntity requestChanges(UUID taskId, String reviewedBy, String note) {
        ApprovalTaskEntity task = findTaskById(taskId);
        task.setStatus(ApprovalStatus.NEEDS_CHANGES);
        task.setReviewedBy(reviewedBy);
        task.setReviewerNote(note);
        task.setReviewedAt(LocalDateTime.now());
        ApprovalTaskEntity saved = approvalTaskRepository.save(task);
        updateContentIdeaStatus(task.getContentIdeaId(), ContentStatus.NEEDS_CHANGES);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ApprovalTaskEntity> listByTenant(UUID tenantId) {
        return approvalTaskRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalTaskEntity> listPending(UUID tenantId) {
        return approvalTaskRepository.findAllByTenantIdAndStatus(tenantId, ApprovalStatus.PENDING);
    }

    private ApprovalTaskEntity findTaskById(UUID taskId) {
        return approvalTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("ApprovalTask not found: " + taskId));
    }

    private void updateContentIdeaStatus(UUID contentIdeaId, ContentStatus status) {
        contentIdeaRepository.findById(contentIdeaId).ifPresent(idea -> {
            idea.setStatus(status);
            contentIdeaRepository.save(idea);
        });
    }
}
