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

    /*
     * The columns below were added by V31 but never mapped, so every step created by
     * DocumentService fell back to the database defaults — TECHNICAL_REVIEW / USER, no SLA, no
     * parallel group. The effect was that the entire workflow authority model was validated at
     * definition time and then discarded: client approval steps were enforced as consultant
     * technical reviews, parallel groups never activated, and no step ever acquired a due date so
     * the due-soon and overdue notifications could not fire.
     */

    /** Contractual authority the step carries. Drives which project permission the decider needs. */
    @Column(name = "authority_type", nullable = false, length = 40)
    private String authorityType = ApprovalAuthority.TECHNICAL_REVIEW.name();

    /** How the reviewer is identified: a named user, a company, or a party role on the project. */
    @Column(name = "assignment_type", nullable = false, length = 30)
    private String assignmentType = ApprovalAssignmentType.USER.name();

    @Column(name = "assignment_organization_id")
    private UUID assignmentOrganizationId;

    @Column(name = "assignment_party_role", length = 40)
    private String assignmentPartyRole;

    /** Optional steps do not block their parallel group from completing. */
    @Column(name = "required", nullable = false)
    private boolean required = true;

    /** Steps sharing a group are decided concurrently rather than in sequence. */
    @Column(name = "parallel_group", length = 80)
    private String parallelGroup;

    @Column(name = "sla_hours")
    private Integer slaHours;

    /** Derived from slaHours when the step becomes actionable; drives SLA notifications. */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

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
