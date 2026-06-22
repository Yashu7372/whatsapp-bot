package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_approval_steps")
public class DocumentApprovalStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "approval_id", nullable = false)
    private UUID approvalId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "step_name", length = 200)
    private String stepName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private TenantUserEntity reviewer;

    @Column(name = "reviewer_email", length = 320)
    private String reviewerEmail;

    @Column(name = "decision", length = 50)
    private String decision;

    @Column(name = "comments", columnDefinition = "text")
    private String comments;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
