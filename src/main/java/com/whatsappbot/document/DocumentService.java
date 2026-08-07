package com.whatsappbot.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.project.OrganizationEntity;
import com.whatsappbot.project.OrganizationRepository;
import com.whatsappbot.project.DocumentNumberService;
import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import com.whatsappbot.storage.StorageService;
import com.whatsappbot.storage.StoredFile;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** Decision values accepted from a reviewer, and the terminal approval statuses they map to. */
    private static final String DECISION_APPROVED = "APPROVED";
    private static final String DECISION_REJECTED = "REJECTED";

    /** Status an approval carries while it still has steps outstanding. */
    private static final String APPROVAL_PENDING = "PENDING";

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentControlWorkflowRepository workflowRepository;
    private final DocumentApprovalRepository approvalRepository;
    private final DocumentApprovalStepRepository stepRepository;
    private final DocumentCommentRepository commentRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final DocumentEncryptionMetadataRepository encryptionMetadataRepository;
    private final StorageService storageService;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;
    private final DocumentAuditService auditService;
    private final ProjectService projectService;
    private final com.whatsappbot.project.ProjectAccessService accessService;
    private final DocumentNumberService numberService;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;

    // ── Document CRUD ──────────────────────────────────────────────────────

    @Transactional
    public DocumentEntity createDocument(UUID tenantId, UUID userId, CreateDocumentRequest req,
                                         MultipartFile file) throws IOException {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        TenantUserEntity user = userRepository.findById(userId).orElse(null);

        DocumentEntity doc = new DocumentEntity();
        doc.setTenant(tenant);
        doc.setTitle(req.title());
        doc.setDocType(req.docType() != null ? req.docType() : "GENERAL");
        doc.setDescription(req.description());
        doc.setTags(req.tags());
        doc.setCreatedBy(user);

        // Attach workflow if one exists for this doc type
        workflowRepository.findByTenantIdAndDocType(tenantId, doc.getDocType())
                .map(DocumentControlWorkflowEntity::getId)
                .ifPresent(doc::setWorkflowId);

        applyProjectContext(tenantId, doc, user, req.projectId());

        doc = documentRepository.save(doc);

        // Version 1
        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setDocumentId(doc.getId());
        version.setTenant(tenant);
        version.setVersionNum(1);
        version.setCreatedBy(user);

        if (file != null && !file.isEmpty()) {
            MediaAssetEntity asset = storeFile(tenantId, userId, file, doc.getId());
            version.setAssetId(asset.getId());
        }
        versionRepository.save(version);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", doc.getTitle());
        payload.put("docType", doc.getDocType());
        payload.put("version", 1);
        payload.put("hasFile", version.getAssetId() != null);
        auditService.record(tenantId, doc.getId(), userId, DocumentAuditService.DOCUMENT_CREATED, payload);

        log.info("Document created. id={} tenant={}", doc.getId(), tenantId);
        return doc;
    }

    /**
     * Lists the documents the caller is entitled to see.
     *
     * <p>Tenant scoping alone is not the sharing boundary the project model promises: one tenant
     * account can host several projects with different contractors on each, and a contractor on
     * one has no business reading another's correspondence. Project documents are therefore
     * filtered to projects the caller's company actually participates in. Documents with no
     * project remain tenant-wide, which is how non-project document flows already worked.
     */
    @Transactional(readOnly = true)
    public List<DocumentEntity> listDocuments(UUID tenantId, UUID userId, String docType) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);

        List<DocumentEntity> documents = docType != null && !docType.isBlank()
                ? documentRepository.findAllByTenantIdAndDocTypeOrderByUpdatedAtDesc(tenantId, docType)
                : documentRepository.findAllByTenantIdOrderByUpdatedAtDesc(tenantId);

        if (accessService.isTenantAdministrator(user)) {
            return documents;
        }
        return documents.stream()
                .filter(d -> d.getProjectId() == null
                        || accessService.canSeeProject(tenantId, d.getProjectId(), user))
                .toList();
    }

    /** Read of a single document with the same project-participation check applied. */
    @Transactional(readOnly = true)
    public DocumentEntity getDocument(UUID tenantId, UUID userId, UUID docId) {
        DocumentEntity doc = getDocument(tenantId, docId);
        if (doc.getProjectId() != null) {
            TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
            accessService.requireProjectVisibility(tenantId, doc.getProjectId(), user);
        }
        return doc;
    }

    /**
     * Tenant-scoped read without the participation check, for internal callers that have already
     * established the caller's right to the document.
     */
    @Transactional(readOnly = true)
    public DocumentEntity getDocument(UUID tenantId, UUID docId) {
        return documentRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not found: " + docId));
    }

    @Transactional
    public DocumentEntity updateDocument(UUID tenantId, UUID userId, UUID docId,
                                         UpdateDocumentRequest req, MultipartFile file) throws IOException {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));

        if (req.title() != null) doc.setTitle(req.title());
        if (req.description() != null) doc.setDescription(req.description());
        if (req.tags() != null) doc.setTags(req.tags());

        if (file != null && !file.isEmpty()) {
            TenantEntity tenant = doc.getTenant();
            TenantUserEntity user = userRepository.findById(userId).orElse(null);

            int nextVersion = doc.getCurrentVersion() + 1;
            doc.setCurrentVersion(nextVersion);

            MediaAssetEntity asset = storeFile(tenantId, userId, file, docId);

            DocumentVersionEntity version = new DocumentVersionEntity();
            version.setDocumentId(docId);
            version.setTenant(tenant);
            version.setVersionNum(nextVersion);
            version.setAssetId(asset.getId());
            version.setChangeNotes(req.changeNotes());
            version.setCreatedBy(user);
            versionRepository.save(version);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", nextVersion);
            payload.put("changeNotes", req.changeNotes());
            payload.put("assetId", asset.getId().toString());
            auditService.record(tenantId, docId, userId, DocumentAuditService.VERSION_UPLOADED, payload);
        }

        return documentRepository.save(doc);
    }

    @Transactional
    public void deleteDocument(UUID tenantId, UUID docId) {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
        documentRepository.delete(doc);
    }

    // ── Version history ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentVersionEntity> listVersions(UUID tenantId, UUID docId) {
        getDocument(tenantId, docId);
        return versionRepository.findAllByDocumentIdOrderByVersionNumDesc(docId);
    }

    // ── Comments ───────────────────────────────────────────────────────────

    @Transactional
    public DocumentCommentEntity addComment(UUID tenantId, UUID userId, UUID docId, String body) {
        DocumentEntity doc = getDocument(tenantId, docId);
        TenantUserEntity user = userRepository.findById(userId).orElse(null);

        DocumentCommentEntity comment = new DocumentCommentEntity();
        comment.setDocumentId(docId);
        comment.setTenant(doc.getTenant());
        comment.setAuthor(user);
        comment.setBody(body);
        return commentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<DocumentCommentEntity> listComments(UUID tenantId, UUID docId) {
        getDocument(tenantId, docId);
        return commentRepository.findAllByDocumentIdOrderByCreatedAtAsc(docId);
    }

    // ── Workflow management ────────────────────────────────────────────────

    @Transactional
    public DocumentControlWorkflowEntity createWorkflow(UUID tenantId, CreateWorkflowRequest req) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        DocumentControlWorkflowEntity wf = new DocumentControlWorkflowEntity();
        wf.setTenant(tenant);
        wf.setName(req.name());
        wf.setDocType(req.docType());
        wf.setSteps(req.steps());
        return workflowRepository.save(wf);
    }

    @Transactional(readOnly = true)
    public List<DocumentControlWorkflowEntity> listWorkflows(UUID tenantId) {
        return workflowRepository.findAllByTenantIdAndActiveTrue(tenantId);
    }

    @Transactional
    public DocumentControlWorkflowEntity updateWorkflow(UUID tenantId, UUID workflowId,
                                                         UpdateWorkflowRequest req) {
        DocumentControlWorkflowEntity wf = workflowRepository.findById(workflowId)
                .filter(w -> w.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        if (req.name() != null) wf.setName(req.name());
        if (req.steps() != null) wf.setSteps(req.steps());
        if (req.active() != null) wf.setActive(req.active());
        return workflowRepository.save(wf);
    }

    // ── Approval workflow ──────────────────────────────────────────────────

    @Transactional
    public DocumentApprovalEntity submitForApproval(UUID tenantId, UUID userId, UUID docId) {
        DocumentEntity doc = getDocument(tenantId, docId);
        TenantUserEntity user = userRepository.findById(userId).orElse(null);

        // Only one PENDING approval at a time
        approvalRepository.findFirstByTenantIdAndDocumentIdAndStatusOrderByStartedAtDesc(
                        tenantId, docId, APPROVAL_PENDING)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Document is already pending approval");
                });

        doc.setStatus(DocumentStatus.IN_REVIEW);
        documentRepository.save(doc);

        DocumentApprovalEntity approval = new DocumentApprovalEntity();
        approval.setDocumentId(docId);
        approval.setTenant(doc.getTenant());
        approval.setWorkflowId(doc.getWorkflowId());
        approval.setInitiatedBy(user);
        approval.setCurrentStep(0);
        DocumentApprovalEntity savedApproval = approvalRepository.save(approval);

        if (doc.getWorkflowId() != null) {
            DocumentControlWorkflowEntity wf = workflowRepository.findById(doc.getWorkflowId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "The workflow attached to this document no longer exists"));
            seedSteps(savedApproval, wf);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", savedApproval.getId().toString());
        payload.put("workflowId", doc.getWorkflowId() != null ? doc.getWorkflowId().toString() : null);
        payload.put("documentVersion", doc.getCurrentVersion());
        payload.put("submittedByEmail", user != null ? user.getEmail() : null);
        auditService.record(tenantId, docId, userId, DocumentAuditService.DOCUMENT_SUBMITTED, payload);

        return savedApproval;
    }

    /**
     * Creates the step records a workflow defines.
     *
     * <p>Fails closed. The previous version caught the parse failure, logged a warning and let
     * submission continue — which produced an approval with no steps, and an approval with no
     * steps is completed by a single decision from anyone. A malformed workflow has to stop the
     * submission, not quietly widen who can approve it.
     */
    private void seedSteps(DocumentApprovalEntity approval, DocumentControlWorkflowEntity wf) {
        List<Map<String, Object>> steps;
        try {
            steps = objectMapper.readValue(wf.getSteps(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Workflow {} has unreadable steps; refusing to start an approval: {}",
                    wf.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The approval workflow for this document type is not valid; fix it before submitting");
        }
        if (steps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The approval workflow for this document type defines no steps");
        }
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> stepDef = steps.get(i);
            DocumentApprovalStepEntity step = new DocumentApprovalStepEntity();
            step.setApprovalId(approval.getId());
            step.setStepIndex(i);
            step.setStepName((String) stepDef.getOrDefault("name", "Step " + (i + 1)));
            step.setReviewerEmail((String) stepDef.get("reviewerEmail"));
            stepRepository.save(step);
        }
    }

    @Transactional
    public DocumentApprovalStepEntity decideStep(UUID tenantId, UUID userId, UUID approvalId,
                                                   String decision, String comments) {
        return decideStep(tenantId, userId, approvalId, decision, comments, null);
    }

    /**
     * Records a reviewer's decision, optionally with the contractual return code.
     *
     * <p>When a {@link ReviewOutcome} is supplied it is authoritative and the approve/reject
     * decision is derived from it: CODE_A and CODE_B let work proceed, CODE_C and CODE_D require
     * a further revision. This is what connects the four-code convention to the approval state —
     * without it the outcome column stayed null and the payment layer's resubmission check could
     * never fire.
     */
    @Transactional
    public DocumentApprovalStepEntity decideStep(UUID tenantId, UUID userId, UUID approvalId,
                                                   String decision, String comments,
                                                   ReviewOutcome reviewOutcome) {
        // A return code, when given, decides the outcome; otherwise fall back to the plain verb.
        String normalisedDecision = reviewOutcome != null
                ? (reviewOutcome.isResubmissionRequired() ? DECISION_REJECTED : DECISION_APPROVED)
                : (decision == null ? "" : decision.trim().toUpperCase());

        if (!DECISION_APPROVED.equals(normalisedDecision) && !DECISION_REJECTED.equals(normalisedDecision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Decision must be " + DECISION_APPROVED + " or " + DECISION_REJECTED
                            + ", or supply a reviewOutcome");
        }

        // Locked for the duration: deciding a step is a read-modify-write over the approval's
        // position, and an approval now gates payment, so a lost update has a money consequence.
        DocumentApprovalEntity approval = approvalRepository.lockByIdAndTenantId(approvalId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Approval not found: " + approvalId));

        // A completed approval is a closed record. Without this, a rejected document could be
        // re-decided into APPROVED, which is exactly the outcome the audit trail must rule out.
        if (!APPROVAL_PENDING.equalsIgnoreCase(approval.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval is already " + approval.getStatus());
        }

        List<DocumentApprovalStepEntity> steps = stepRepository
                .findAllByApprovalIdOrderByStepIndex(approvalId);

        int currentIdx = approval.getCurrentStep();
        DocumentApprovalStepEntity currentStep = steps.stream()
                .filter(s -> s.getStepIndex() == currentIdx)
                .findFirst()
                .orElseGet(() -> {
                    DocumentApprovalStepEntity s = new DocumentApprovalStepEntity();
                    s.setApprovalId(approvalId);
                    s.setStepIndex(currentIdx);
                    return s;
                });

        TenantUserEntity user = requireActiveUser(tenantId, userId);
        assertMayDecide(user, currentStep);
        currentStep.setReviewer(user);
        currentStep.setDecision(normalisedDecision);
        currentStep.setComments(comments);
        currentStep.setDecidedAt(LocalDateTime.now());
        stepRepository.save(currentStep);

        DocumentEntity doc = documentRepository.findByIdAndTenantId(approval.getDocumentId(), tenantId)
                .orElseThrow();

        // Record the return code on the document so the payment layer can see whether a
        // resubmission is outstanding.
        if (reviewOutcome != null) {
            doc.setReviewOutcome(reviewOutcome);
        }

        boolean approvalComplete;
        if (DECISION_REJECTED.equals(normalisedDecision)) {
            approval.setStatus(DECISION_REJECTED);
            approval.setCompletedAt(LocalDateTime.now());
            doc.setStatus(DocumentStatus.REJECTED);
            approvalComplete = true;
        } else {
            boolean moreSteps = steps.stream().anyMatch(s -> s.getStepIndex() > currentIdx);
            if (moreSteps) {
                approval.setCurrentStep(currentIdx + 1);
                approvalComplete = false;
            } else {
                approval.setStatus(DECISION_APPROVED);
                approval.setCompletedAt(LocalDateTime.now());
                doc.setStatus(DocumentStatus.APPROVED);
                approvalComplete = true;
            }
        }

        approvalRepository.save(approval);
        documentRepository.save(doc);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("approvalId", approvalId.toString());
        auditPayload.put("stepIndex", currentIdx);
        auditPayload.put("stepName", currentStep.getStepName());
        auditPayload.put("decision", normalisedDecision);
        auditPayload.put("reviewOutcome", reviewOutcome != null ? reviewOutcome.name() : null);
        auditPayload.put("comments", comments);
        auditPayload.put("decidedByEmail", user.getEmail());
        auditPayload.put("approvalComplete", approvalComplete);
        auditPayload.put("documentVersion", doc.getCurrentVersion());

        auditService.record(tenantId, doc.getId(), userId,
                DECISION_REJECTED.equals(normalisedDecision)
                        ? DocumentAuditService.APPROVAL_REJECTED
                        : DocumentAuditService.APPROVAL_APPROVED,
                auditPayload);

        return currentStep;
    }

    /**
     * Places a document into project delivery: records which project and which company it came
     * from, takes the next reference in that project's series for its type, and sets the
     * contractual reply deadline from the same series.
     *
     * <p>Documents without a project keep working exactly as before — this is additive, so
     * existing non-project document flows are untouched.
     */
    private void applyProjectContext(UUID tenantId, DocumentEntity doc, TenantUserEntity user,
                                      UUID projectId) {
        if (projectId == null) {
            return;
        }
        ProjectEntity project = projectService.get(tenantId, projectId);
        doc.setProjectId(project.getId());

        // The originating company is taken from the user's own organization, never from the
        // request: a contractor must not be able to raise a document as though it came from
        // the consultant.
        UUID originatorOrgId = user != null ? user.getOrganizationId() : null;
        doc.setOriginatorOrgId(originatorOrgId);

        String orgCode = originatorOrgId == null ? null
                : organizationRepository.findByIdAndTenantId(originatorOrgId, tenantId)
                        .map(OrganizationEntity::getOrgCode)
                        .orElse(null);

        doc.setDocumentCode(
                numberService.nextReference(tenantId, project.getId(), doc.getDocType(), orgCode));

        numberService.responseDaysFor(tenantId, project.getId(), doc.getDocType())
                .ifPresent(days -> doc.setDueAt(LocalDateTime.now().plusDays(days)));
    }

    /**
     * Loads the acting user and confirms they still belong to this tenant and are active.
     *
     * <p>The previous implementation used {@code findById(userId).orElse(null)} and carried on
     * with a null reviewer, so a deleted or foreign user id produced an approval attributed to
     * nobody.
     */
    private TenantUserEntity requireActiveUser(UUID tenantId, UUID userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getTenant().getId().equals(tenantId))
                .filter(TenantUserEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "User is not an active member of this tenant"));
    }

    /**
     * Enforces who is allowed to decide an approval step.
     *
     * <p>Previously the only check was that the approval belonged to the caller's tenant, so any
     * authenticated user — including a read-only one — could approve any step of any document
     * and have their name recorded as the approver. Where approval releases payment, that is the
     * one thing the workflow has to prevent.
     *
     * <p>A step names its reviewer by email when the workflow is seeded. Where that name is
     * present it is authoritative. Where a step has no named reviewer (a document with no
     * workflow attached), the decision falls back to tenant administrators and managers rather
     * than being open to everyone.
     */
    private void assertMayDecide(TenantUserEntity user, DocumentApprovalStepEntity step) {
        if (user.getRole() == UserRole.VIEWER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Read-only users cannot decide approval steps");
        }

        String assignedReviewer = step.getReviewerEmail();
        if (assignedReviewer != null && !assignedReviewer.isBlank()) {
            if (!assignedReviewer.trim().equalsIgnoreCase(user.getEmail())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This step is assigned to another reviewer");
            }
            return;
        }

        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator or manager can decide an unassigned step");
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentApprovalEntity> listApprovals(UUID tenantId, UUID docId) {
        getDocument(tenantId, docId);
        return approvalRepository.findAllByTenantIdAndDocumentIdOrderByStartedAtDesc(tenantId, docId);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private MediaAssetEntity storeFile(UUID tenantId, UUID userId, MultipartFile file, UUID refId)
            throws IOException {
        StoredFile stored = storageService.store(
                tenantId, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream(), file.getSize());

        TenantEntity tenant = tenantRepository.findById(tenantId).orElseThrow();
        TenantUserEntity user = userRepository.findById(userId).orElse(null);

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setTenant(tenant);
        asset.setOriginalName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload");
        asset.setStoredPath(stored.storedPath());
        asset.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        asset.setSizeBytes(stored.sizeBytes());
        asset.setAssetType("DOCUMENT");
        asset.setRefId(refId);
        asset.setUploadedBy(user);
        return mediaAssetRepository.save(asset);
    }

    // ── Zero-knowledge encrypted document upload ───────────────────────────

    @Transactional
    public DocumentEntity createEncryptedDocument(UUID tenantId, UUID userId,
                                                   String metadataJson,
                                                   MultipartFile encryptedFile) throws IOException {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        TenantUserEntity user = userRepository.findById(userId).orElse(null);

        Map<String, Object> meta = objectMapper.readValue(metadataJson, new TypeReference<>() {});

        String title       = (String) meta.getOrDefault("title", "Untitled");
        String docType     = (String) meta.getOrDefault("docType", "GENERAL");
        String description = (String) meta.getOrDefault("description", null);

        DocumentEntity doc = new DocumentEntity();
        doc.setTenant(tenant);
        doc.setTitle(title);
        doc.setDocType(docType);
        doc.setDescription(description);
        doc.setCreatedBy(user);

        workflowRepository.findByTenantIdAndDocType(tenantId, docType)
                .map(DocumentControlWorkflowEntity::getId)
                .ifPresent(doc::setWorkflowId);

        doc = documentRepository.save(doc);

        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setDocumentId(doc.getId());
        version.setTenant(tenant);
        version.setVersionNum(1);
        version.setCreatedBy(user);

        if (encryptedFile != null && !encryptedFile.isEmpty()) {
            MediaAssetEntity asset = storeEncryptedFile(tenant, user, encryptedFile, doc.getId());
            version.setAssetId(asset.getId());

            // Persist encryption envelope
            DocumentEncryptionMetadataEntity enc = new DocumentEncryptionMetadataEntity();
            enc.setAsset(asset);
            enc.setTenant(tenant);
            enc.setEncryptionAlg((String) meta.getOrDefault("encryptionAlg", "AES-GCM-256"));
            enc.setKeyId((String) meta.get("keyId"));
            enc.setEncryptedFileKey((String) meta.get("encryptedFileKey"));
            String iv = (String) meta.get("ivBase64");
            if (iv == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ivBase64 is required");
            enc.setIvBase64(iv);
            enc.setAuthTagBase64((String) meta.get("authTagBase64"));
            String cipherHash = (String) meta.get("ciphertextSha256");
            if (cipherHash == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ciphertextSha256 is required");
            enc.setCiphertextSha256(cipherHash);
            enc.setPlaintextSha256((String) meta.get("plaintextSha256"));
            encryptionMetadataRepository.save(enc);
        }

        versionRepository.save(version);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", doc.getTitle());
        payload.put("docType", doc.getDocType());
        payload.put("version", 1);
        payload.put("mode", "ZERO_KNOWLEDGE");
        auditService.record(tenantId, doc.getId(), userId, DocumentAuditService.DOCUMENT_CREATED, payload);

        log.info("Encrypted document created. id={} tenant={}", doc.getId(), tenantId);
        return doc;
    }

    private MediaAssetEntity storeEncryptedFile(TenantEntity tenant, TenantUserEntity user,
                                                  MultipartFile file, UUID refId) throws IOException {
        StoredFile stored = storageService.store(
                tenant.getId(), file.getOriginalFilename(),
                "application/octet-stream", file.getInputStream(), file.getSize());

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setTenant(tenant);
        asset.setOriginalName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "ciphertext.bin");
        asset.setStoredPath(stored.storedPath());
        asset.setObjectKey(stored.storedPath());
        asset.setContentType("application/octet-stream");
        asset.setSizeBytes(stored.sizeBytes());
        asset.setAssetType("DOCUMENT_CIPHERTEXT");
        asset.setRefId(refId);
        asset.setUploadedBy(user);
        asset.setCreatedBy(user);
        asset.setVisibility("PRIVATE");
        return mediaAssetRepository.save(asset);
    }

    // ── Request records ────────────────────────────────────────────────────

    public record CreateDocumentRequest(String title, String docType, String description, String[] tags,
                                         UUID projectId) {}
    public record UpdateDocumentRequest(String title, String description, String[] tags, String changeNotes) {}
    public record CreateWorkflowRequest(String name, String docType, String steps) {}
    public record UpdateWorkflowRequest(String name, String steps, Boolean active) {}
}
