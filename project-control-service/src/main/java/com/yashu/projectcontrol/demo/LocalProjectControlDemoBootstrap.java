package com.yashu.projectcontrol.demo;

import com.yashu.projectcontrol.access.IdentityService;
import com.yashu.projectcontrol.commercial.CommercialService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.document.DocumentWorkflowService;
import com.yashu.projectcontrol.document.LocalDocumentContentStore;
import com.yashu.projectcontrol.evidence.DocumentEvidenceService;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.verification.VerificationService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Local-profile-only business fixture for the canonical Project Control demo.
 *
 * <p>The bootstrap does not create alternate demo business logic. It creates normal
 * Project Control resources through the existing application services, then leaves
 * them at a useful starting point for the live demo/Postman collection:</p>
 *
 * <pre>
 * Aurelia Creek Residences -> Construction -> Zone B -> CHW Installation
 * controlled drawing Rev 03 -> ITR workflow at Site Team
 * WVP-CHW-001 draft claiming 320 m -> controlled evidence attached
 * contract item 320 m @ AED 400/m
 * </pre>
 */
@Component
@Profile("local")
@Order(100)
@ConditionalOnProperty(name = "project-control.demo.enabled", havingValue = "true", matchIfMissing = true)
class LocalProjectControlDemoBootstrap implements ApplicationRunner {

    static final String WORKSPACE_CODE = "AURELIA-DEMO";
    static final String PROJECT_CODE = "AUR-CRK";
    static final String CHW_SCOPE_CODE = "CHW-ZB-005";
    static final String DOCUMENT_NUMBER = "AUR-CRK-MEP-SD-004";
    static final String DOCUMENT_REVISION = "03";
    static final String DOCUMENT_WORKFLOW_CODE = "ITR_APPROVAL";
    static final String VERIFICATION_WORKFLOW_CODE = "WORK_VERIFICATION";
    static final String FIRST_VERIFICATION_PACKAGE = "WVP-CHW-001";
    static final String CONTRACT_NUMBER = "AUR-GB-CHW-001";
    static final String CONTRACT_ITEM_CODE = "CHW-QTY";

    static final String CLIENT_LEGAL_NAME = "Aurelia Developments PJSC";
    static final String CONTRACTOR_LEGAL_NAME = "GulfBuild Contracting LLC";
    static final String CONSULTANT_LEGAL_NAME = "Meridian Engineering Consultants LLC";

    private static final Logger log = LoggerFactory.getLogger(LocalProjectControlDemoBootstrap.class);

    private static final byte[] DEMO_PDF = ("""
            %PDF-1.4
            1 0 obj
            << /Type /Catalog >>
            endobj
            % Project Control local CHW demo drawing placeholder
            %%EOF
            """).getBytes(StandardCharsets.US_ASCII);

    private static final String WORKFLOW_VIEW_RESPONSIBILITIES = """
            ["SITE_TEAM","QCE","QC_DC","CONSULTANT_INSPECTOR","CONSULTANT_RE","VIEWER","CONTRACTOR_QS","CLIENT_QS"]
            """.trim();

    private final JdbcClient jdbc;
    private final WorkspaceService workspaceService;
    private final OrganizationService organizationService;
    private final ProjectService projectService;
    private final ParticipationService participationService;
    private final ScopeService scopeService;
    private final IdentityService identityService;
    private final DocumentService documentService;
    private final LocalDocumentContentStore contentStore;
    private final DocumentEvidenceService evidenceService;
    private final WorkflowService workflowService;
    private final DocumentWorkflowService documentWorkflowService;
    private final VerificationService verificationService;
    private final CommercialService commercialService;

    LocalProjectControlDemoBootstrap(
            JdbcClient jdbc,
            WorkspaceService workspaceService,
            OrganizationService organizationService,
            ProjectService projectService,
            ParticipationService participationService,
            ScopeService scopeService,
            IdentityService identityService,
            DocumentService documentService,
            LocalDocumentContentStore contentStore,
            DocumentEvidenceService evidenceService,
            WorkflowService workflowService,
            DocumentWorkflowService documentWorkflowService,
            VerificationService verificationService,
            CommercialService commercialService) {
        this.jdbc = jdbc;
        this.workspaceService = workspaceService;
        this.organizationService = organizationService;
        this.projectService = projectService;
        this.participationService = participationService;
        this.scopeService = scopeService;
        this.identityService = identityService;
        this.documentService = documentService;
        this.contentStore = contentStore;
        this.evidenceService = evidenceService;
        this.workflowService = workflowService;
        this.documentWorkflowService = documentWorkflowService;
        this.verificationService = verificationService;
        this.commercialService = commercialService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (scenarioExists()) {
            log.info("Project Control local demo already exists: {} / {}", WORKSPACE_CODE, PROJECT_CODE);
            return;
        }

        LocalDocumentContentStore.StoredContent storedPdf = null;
        try {
            var workspace = workspaceService.create(WORKSPACE_CODE, "Aurelia Project Control Demo");
            var project = projectService.create(
                    workspace.id(),
                    PROJECT_CODE,
                    "Aurelia Creek Residences",
                    "Canonical local demo for controlled CHW work, evidence, verification and payment provenance",
                    LocalDate.of(2025, 9, 1),
                    LocalDate.of(2028, 3, 31),
                    "AED",
                    "Asia/Dubai");

            var client = organizationService.create(CLIENT_LEGAL_NAME, "Aurelia Developments");
            var contractor = organizationService.create(CONTRACTOR_LEGAL_NAME, "GulfBuild");
            var consultant = organizationService.create(CONSULTANT_LEGAL_NAME, "Meridian Consultants");

            var clientParticipant = participationService.create(
                    project.id(), client.id(), "CLIENT", null, null, null);
            var contractorParticipant = participationService.create(
                    project.id(), contractor.id(), "MAIN_CONTRACTOR", null, null, null);
            var consultantParticipant = participationService.create(
                    project.id(), consultant.id(), "CONSULTANT", null, null, null);

            var construction = scopeService.create(
                    project.id(), null, "STAGE", "CONSTRUCTION", "Construction",
                    "Physical execution and verification stage", null, null, "{}");
            var zoneB = scopeService.create(
                    project.id(), construction.id(), "ZONE", "ZONE_B", "Zone B",
                    "Zone B execution area", null, null, "{}");
            var chw = scopeService.create(
                    project.id(), zoneB.id(), "ACTIVITY_GROUP", CHW_SCOPE_CODE,
                    "Chilled-Water Piping - Zone B",
                    "Canonical measurable CHW installation scope used by the Project Control demo",
                    null, null, "{\"discipline\":\"MEP\",\"system\":\"CHW\"}");

            scopeService.assignParticipant(project.id(), chw.id(), contractorParticipant.id(), "Executes CHW installation");
            scopeService.assignParticipant(project.id(), chw.id(), consultantParticipant.id(), "Reviews and verifies CHW installation");
            scopeService.assignParticipant(project.id(), chw.id(), clientParticipant.id(), "Client commercial acceptance and payment");

            enableCapabilities(project.id(), chw.id());

            var admin = identityService.getUserByEmail("admin@local.demo");
            var site = identityService.getUserByEmail("site@local.demo");
            var qce = identityService.getUserByEmail("qce@local.demo");
            var qcdc = identityService.getUserByEmail("qcdc@local.demo");
            var inspector = identityService.getUserByEmail("inspector@local.demo");
            var re = identityService.getUserByEmail("re@local.demo");
            var viewer = identityService.getUserByEmail("viewer@local.demo");
            var contractorQs = identityService.getUserByEmail("contractor.qs@local.demo");
            var clientQs = identityService.getUserByEmail("client.qs@local.demo");

            identityService.addWorkspaceMembership(admin.id(), workspace.id(), "PROJECT_ADMIN", null, null);

            assignUser(site, contractor.id(), contractorParticipant.id(), project.id(), chw.id(), "SITE_TEAM", "CONTRIBUTE");
            assignUser(qce, contractor.id(), contractorParticipant.id(), project.id(), chw.id(), "QCE", "APPROVE");
            assignUser(qcdc, contractor.id(), contractorParticipant.id(), project.id(), chw.id(), "QC_DC", "CONTRIBUTE");
            assignUser(contractorQs, contractor.id(), contractorParticipant.id(), project.id(), chw.id(), "CONTRACTOR_QS", "MANAGE");

            assignUser(inspector, consultant.id(), consultantParticipant.id(), project.id(), chw.id(), "CONSULTANT_INSPECTOR", "APPROVE");
            assignUser(re, consultant.id(), consultantParticipant.id(), project.id(), chw.id(), "CONSULTANT_RE", "APPROVE");
            assignUser(viewer, consultant.id(), consultantParticipant.id(), project.id(), chw.id(), "VIEWER", "VIEW");

            assignUser(clientQs, client.id(), clientParticipant.id(), project.id(), chw.id(), "CLIENT_QS", "APPROVE");

            var documentWorkflow = createDocumentWorkflow(project.id(), chw.id());
            var verificationWorkflow = createVerificationWorkflow(project.id(), chw.id());

            var document = documentService.create(
                    project.id(),
                    chw.id(),
                    contractor.id(),
                    DOCUMENT_NUMBER,
                    null,
                    "SHOP_DRAWING",
                    "CHW Zone B Routing Shop Drawing",
                    "Controlled chilled-water routing drawing supporting installation and verification",
                    "PROJECT_SHARED",
                    "{\"workReference\":\"CHW-ZB-005\",\"discipline\":\"MEP\"}");

            storedPdf = contentStore.storePdf(DEMO_PDF, "AUR-CRK-MEP-SD-004-R03.pdf");
            var revision = documentService.addRevision(
                    document.id(),
                    DOCUMENT_REVISION,
                    "Rerouted CHW around the fire-rated shaft and updated valve access clearance after coordination review",
                    storedPdf.contentUri(),
                    storedPdf.sha256(),
                    storedPdf.originalFilename(),
                    storedPdf.mediaType(),
                    storedPdf.sizeBytes());

            evidenceService.record(
                    admin.id(),
                    document.id(),
                    revision.id(),
                    "CHW_DRAWING_EXTRACTOR",
                    "1.0",
                    evidenceJson());

            var documentWorkflowInstance = documentWorkflowService.startForDocument(
                    site.id(),
                    document.id(),
                    documentWorkflow.id(),
                    "SD-004-R03",
                    "Review " + DOCUMENT_NUMBER + " Rev " + DOCUMENT_REVISION,
                    "{\"revisionId\":\"" + revision.id() + "\",\"workReference\":\"CHW-ZB-005\"}");

            var contract = commercialService.createContract(
                    admin.id(),
                    project.id(),
                    clientParticipant.id(),
                    contractorParticipant.id(),
                    CONTRACT_NUMBER,
                    "MAIN_CONTRACT",
                    "AED",
                    new BigDecimal("128000"),
                    "CONTRACT_SHARED");
            var contractItem = commercialService.createContractItem(
                    admin.id(),
                    project.id(),
                    contract.id(),
                    chw.id(),
                    CONTRACT_ITEM_CODE,
                    "CHW measured and accepted quantity - Zone B",
                    "QUANTITY_RATE",
                    "m",
                    new BigDecimal("320"),
                    new BigDecimal("400"),
                    new BigDecimal("128000"),
                    LocalDate.of(2026, 10, 15));

            var firstPackage = verificationService.createPackage(
                    contractorQs.id(),
                    project.id(),
                    chw.id(),
                    FIRST_VERIFICATION_PACKAGE,
                    "INSTALLED_QUANTITY",
                    contractor.id(),
                    null);
            var firstItem = verificationService.addItem(
                    contractorQs.id(),
                    project.id(),
                    firstPackage.id(),
                    firstPackage.version(),
                    "scope://" + project.id() + "/" + chw.id() + "/CHW-ZB-005",
                    null,
                    new BigDecimal("320"),
                    "m",
                    "320 m chilled-water piping installed in Zone B and offered for verification");
            firstPackage = verificationService.getPackage(
                    contractorQs.id(), project.id(), firstPackage.id()).verificationPackage();
            verificationService.addEvidence(
                    contractorQs.id(),
                    project.id(),
                    firstPackage.id(),
                    firstPackage.version(),
                    revision.id(),
                    "INSTALLATION_RECORD",
                    "PROJECT_SHARED",
                    true);

            log.info(
                    "Seeded Project Control demo: project={} scope={} document={} workflow={} verificationPackage={} contract={} contractItem={} verificationWorkflow={}",
                    project.id(), chw.id(), document.id(), documentWorkflowInstance.id(), firstPackage.id(),
                    contract.id(), contractItem.id(), verificationWorkflow.id());
            log.info("Demo accounts use password Project123!; start with site@local.demo and the Postman collection.");
        } catch (RuntimeException ex) {
            if (storedPdf != null) {
                contentStore.deleteQuietly(storedPdf.contentUri());
            }
            throw ex;
        }
    }

    private boolean scenarioExists() {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM projects p
                        JOIN workspaces w ON w.id = p.workspace_id
                        WHERE w.code = :workspaceCode AND p.code = :projectCode
                        """)
                .param("workspaceCode", WORKSPACE_CODE)
                .param("projectCode", PROJECT_CODE)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    private void enableCapabilities(java.util.UUID projectId, java.util.UUID scopeId) {
        for (String capability : new String[]{
                "DOCUMENT_CONTROL",
                "INSPECTION",
                "VERIFICATION",
                "QUANTITY_MEASUREMENT",
                "VALUATION",
                "IPC",
                "PAYMENT",
                "AI_DOCUMENT_INTELLIGENCE"}) {
            scopeService.setCapability(projectId, scopeId, capability, true, "{}");
        }
    }

    private void assignUser(
            IdentityService.UserView user,
            java.util.UUID organizationId,
            java.util.UUID participantId,
            java.util.UUID projectId,
            java.util.UUID scopeId,
            String responsibility,
            String accessLevel) {
        identityService.addOrganizationMembership(user.id(), organizationId, responsibility, null, null);
        identityService.addScopeAssignment(
                user.id(), projectId, scopeId, participantId, responsibility, accessLevel, null, null);
    }

    private WorkflowService.DefinitionView createDocumentWorkflow(
            java.util.UUID projectId,
            java.util.UUID scopeId) {
        var definition = workflowService.createDefinition(
                projectId,
                DOCUMENT_WORKFLOW_CODE,
                1,
                "CHW Controlled Drawing / ITR Review",
                "DOCUMENT_REVIEW",
                "INSPECTION");
        addStep(definition.id(), 1, "SITE_TEAM", "Site Team Raise", "SUBMIT", "SITE_TEAM");
        addStep(definition.id(), 2, "QCE_VERIFY", "QCE Verification", "VERIFY", "QCE");
        addStep(definition.id(), 3, "QC_DC_RECEIVE", "QC/DC Receiving", "RECEIVE", "QC_DC");
        addStep(definition.id(), 4, "CONSULTANT_INSPECT", "Consultant Inspector Review", "REVIEW", "CONSULTANT_INSPECTOR");
        addStep(definition.id(), 5, "RE_FINAL_APPROVAL", "Consultant RE Final Approval", "APPROVE", "CONSULTANT_RE");
        definition = workflowService.activateDefinition(definition.id());
        workflowService.setScopeBinding(projectId, scopeId, definition.id(), true, "{}");
        return definition;
    }

    private WorkflowService.DefinitionView createVerificationWorkflow(
            java.util.UUID projectId,
            java.util.UUID scopeId) {
        var definition = workflowService.createDefinition(
                projectId,
                VERIFICATION_WORKFLOW_CODE,
                1,
                "CHW Work Verification",
                "WORK_ACCEPTANCE",
                "VERIFICATION");
        workflowService.addStep(
                definition.id(),
                1,
                "CONSULTANT_VERIFY",
                "Consultant CHW Verification",
                "ACCEPT",
                assignmentJson("CONSULTANT_INSPECTOR"),
                "{}");
        definition = workflowService.activateDefinition(definition.id());
        workflowService.setScopeBinding(projectId, scopeId, definition.id(), true, "{}");
        return definition;
    }

    private void addStep(
            java.util.UUID definitionId,
            int sequence,
            String code,
            String name,
            String completionAction,
            String responsibility) {
        workflowService.addStep(
                definitionId,
                sequence,
                code,
                name,
                completionAction,
                assignmentJson(responsibility),
                "{}");
    }

    private static String assignmentJson(String responsibility) {
        return "{\"act\":{\"responsibilityCodes\":[\"" + responsibility
                + "\"]},\"view\":{\"responsibilityCodes\":" + WORKFLOW_VIEW_RESPONSIBILITIES + "}}";
    }

    private static String evidenceJson() {
        return """
                {
                  "drawingNumber":"AUR-CRK-MEP-SD-004",
                  "revision":"03",
                  "discipline":"MEP",
                  "system":"CHW",
                  "location":"Zone B",
                  "workReference":"CHW-ZB-005",
                  "claimedInstalledQuantity":{"value":320,"unit":"m"},
                  "references":[
                    {"type":"METHOD_STATEMENT","reference":"MS-CHW-002","revision":"02"},
                    {"type":"MATERIAL_APPROVAL","reference":"MAT-044"},
                    {"type":"INSPECTION_REQUEST","reference":"IR-119"},
                    {"type":"PRESSURE_TEST","reference":"PT-031"}
                  ],
                  "extractorFindings":[
                    {"code":"ROUTING_CHANGE","text":"Routing change detected around the fire-rated shaft."},
                    {"code":"ACCESS_CLEARANCE","text":"Valve access clearance annotation updated in this revision."},
                    {"code":"SUPPORTING_REFERENCES","text":"Method statement, material approval, inspection request and pressure-test references are present."}
                  ],
                  "limitations":[
                    "Extracted document evidence does not prove the physically installed quantity.",
                    "Field verification is still required before accepted quantity can become commercial truth."
                  ]
                }
                """;
    }
}
