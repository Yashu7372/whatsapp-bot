package com.whatsappbot.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

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

        log.info("Document created. id={} tenant={}", doc.getId(), tenantId);
        return doc;
    }

    @Transactional(readOnly = true)
    public List<DocumentEntity> listDocuments(UUID tenantId, String docType) {
        if (docType != null && !docType.isBlank()) {
            return documentRepository.findAllByTenantIdAndDocTypeOrderByUpdatedAtDesc(tenantId, docType);
        }
        return documentRepository.findAllByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public DocumentEntity getDocument(UUID tenantId, UUID docId) {
        return documentRepository.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
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
        approvalRepository.findFirstByDocumentIdAndStatusOrderByStartedAtDesc(docId, "PENDING")
                .ifPresent(existing -> {
                    throw new IllegalStateException("Document is already pending approval");
                });

        doc.setStatus(DocumentStatus.IN_REVIEW);
        documentRepository.save(doc);

        DocumentApprovalEntity approval = new DocumentApprovalEntity();
        approval.setDocumentId(docId);
        approval.setTenant(doc.getTenant());
        approval.setWorkflowId(doc.getWorkflowId());
        approval.setInitiatedBy(user);

        // Seed step records from workflow if available
        if (doc.getWorkflowId() != null) {
            workflowRepository.findById(doc.getWorkflowId()).ifPresent(wf -> {
                try {
                    List<Map<String, Object>> steps = objectMapper.readValue(
                            wf.getSteps(), new TypeReference<>() {});
                    approval.setCurrentStep(0);
                    DocumentApprovalEntity saved = approvalRepository.save(approval);
                    for (int i = 0; i < steps.size(); i++) {
                        Map<String, Object> stepDef = steps.get(i);
                        DocumentApprovalStepEntity step = new DocumentApprovalStepEntity();
                        step.setApprovalId(saved.getId());
                        step.setStepIndex(i);
                        step.setStepName((String) stepDef.getOrDefault("name", "Step " + (i + 1)));
                        step.setReviewerEmail((String) stepDef.get("reviewerEmail"));
                        stepRepository.save(step);
                    }
                    return;
                } catch (Exception e) {
                    log.warn("Could not parse workflow steps: {}", e.getMessage());
                }
            });
        }

        return approvalRepository.save(approval);
    }

    @Transactional
    public DocumentApprovalStepEntity decideStep(UUID tenantId, UUID userId, UUID approvalId,
                                                   String decision, String comments) {
        DocumentApprovalEntity approval = approvalRepository.findById(approvalId)
                .filter(a -> a.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

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

        TenantUserEntity user = userRepository.findById(userId).orElse(null);
        currentStep.setReviewer(user);
        currentStep.setDecision(decision);
        currentStep.setComments(comments);
        currentStep.setDecidedAt(LocalDateTime.now());
        stepRepository.save(currentStep);

        DocumentEntity doc = documentRepository.findByIdAndTenantId(approval.getDocumentId(), tenantId)
                .orElseThrow();

        if ("REJECTED".equalsIgnoreCase(decision)) {
            approval.setStatus("REJECTED");
            approval.setCompletedAt(LocalDateTime.now());
            doc.setStatus(DocumentStatus.REJECTED);
        } else if ("APPROVED".equalsIgnoreCase(decision)) {
            boolean moreSteps = steps.stream().anyMatch(s -> s.getStepIndex() > currentIdx);
            if (moreSteps) {
                approval.setCurrentStep(currentIdx + 1);
            } else {
                approval.setStatus("APPROVED");
                approval.setCompletedAt(LocalDateTime.now());
                doc.setStatus(DocumentStatus.APPROVED);
            }
        }

        approvalRepository.save(approval);
        documentRepository.save(doc);
        return currentStep;
    }

    @Transactional(readOnly = true)
    public List<DocumentApprovalEntity> listApprovals(UUID tenantId, UUID docId) {
        getDocument(tenantId, docId);
        return approvalRepository.findAllByDocumentIdOrderByStartedAtDesc(docId);
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

    public record CreateDocumentRequest(String title, String docType, String description, String[] tags) {}
    public record UpdateDocumentRequest(String title, String description, String[] tags, String changeNotes) {}
    public record CreateWorkflowRequest(String name, String docType, String steps) {}
    public record UpdateWorkflowRequest(String name, String steps, Boolean active) {}
}
