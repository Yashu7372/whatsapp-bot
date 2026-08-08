package com.whatsappbot.document;

import com.whatsappbot.project.ProjectPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The workflow authority model was defined, validated, and then discarded: every approval step
 * took the database defaults, so a CLIENT_APPROVAL stage was enforced as a consultant technical
 * review and no step ever gained a due date. These pin the mapping that was missing.
 */
class ApprovalStepSeedingTest {

    @Test
    @DisplayName("each authority demands its own project permission")
    void authoritiesMapToDistinctPermissions() {
        assertThat(ApprovalAuthority.INTERNAL_REVIEW.permission())
                .isEqualTo(ProjectPermission.DOCUMENT_REVIEW_INTERNAL);
        assertThat(ApprovalAuthority.TECHNICAL_REVIEW.permission())
                .isEqualTo(ProjectPermission.DOCUMENT_REVIEW_TECHNICAL);
        assertThat(ApprovalAuthority.CLIENT_APPROVAL.permission())
                .isEqualTo(ProjectPermission.DOCUMENT_APPROVE_CLIENT);
        assertThat(ApprovalAuthority.COMMERCIAL_CERTIFICATION.permission())
                .isEqualTo(ProjectPermission.DOCUMENT_CERTIFY_COMMERCIAL);

        // The bug in one line: client approval must not resolve to the consultant's permission.
        assertThat(ApprovalAuthority.CLIENT_APPROVAL.permission())
                .isNotEqualTo(ApprovalAuthority.TECHNICAL_REVIEW.permission());
    }

    @Test
    @DisplayName("a new step defaults to a sequential named review until the workflow says otherwise")
    void entityDefaultsAreExplicit() {
        DocumentApprovalStepEntity step = new DocumentApprovalStepEntity();
        assertThat(step.getAuthorityType()).isEqualTo(ApprovalAuthority.TECHNICAL_REVIEW.name());
        assertThat(step.getAssignmentType()).isEqualTo(ApprovalAssignmentType.USER.name());
        assertThat(step.isRequired()).isTrue();
        assertThat(step.getDueAt()).isNull();
    }

    @Test
    @DisplayName("the workflow fields the seeding bug dropped are mapped and settable")
    void authorityFieldsRoundTrip() {
        DocumentApprovalStepEntity step = new DocumentApprovalStepEntity();
        step.setAuthorityType(ApprovalAuthority.CLIENT_APPROVAL.name());
        step.setAssignmentType(ApprovalAssignmentType.PARTY_ROLE.name());
        step.setAssignmentPartyRole("CLIENT");
        step.setParallelGroup("client-review");
        step.setSlaHours(48);
        step.setRequired(false);

        assertThat(step.getAuthorityType()).isEqualTo("CLIENT_APPROVAL");
        assertThat(step.getAssignmentPartyRole()).isEqualTo("CLIENT");
        assertThat(step.getParallelGroup()).isEqualTo("client-review");
        assertThat(step.getSlaHours()).isEqualTo(48);
        assertThat(step.isRequired()).isFalse();
    }

    @Test
    @DisplayName("an unknown authority fails loudly rather than defaulting")
    void unknownAuthorityIsRejected() {
        assertThatThrownBy(() -> ApprovalAuthority.of("RUBBER_STAMP"))
                .hasMessageContaining("Unsupported workflow authority");
        assertThatThrownBy(() -> ApprovalAssignmentType.of("WHOEVER"))
                .hasMessageContaining("Unsupported workflow assignment type");
    }
}
