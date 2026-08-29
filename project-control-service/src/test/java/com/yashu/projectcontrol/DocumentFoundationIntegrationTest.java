package com.yashu.projectcontrol;

import com.yashu.projectcontrol.document.DocumentNumberService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class DocumentFoundationIntegrationTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private DocumentNumberService numberService;

    @Autowired
    private DocumentService documentService;

    @Test
    void documentRegisterSupportsOptionalScopeImmutableRevisionsFlexibleMetadataAndIndependentNumberSeries() {
        var workspace = workspaceService.create("DOC-FOUNDATION", "Document Foundation Workspace");
        var project = projectService.create(
                workspace.id(), "DOC-A", "Document Foundation Project", null,
                null, null, "AED", "Asia/Dubai");
        var otherProject = projectService.create(
                workspace.id(), "DOC-B", "Other Project", null,
                null, null, "AED", "Asia/Dubai");

        var tender = scopeService.create(
                project.id(), null, "STAGE", "TENDER", "Tender", null,
                null, null, "{}");
        var construction = scopeService.create(
                project.id(), null, "STAGE", "CONSTRUCTION", "Construction", null,
                null, null, "{}");
        var mep = scopeService.create(
                project.id(), construction.id(), "DISCIPLINE", "MEP", "MEP", null,
                null, null, "{}");
        var foreignScope = scopeService.create(
                otherProject.id(), null, "STAGE", "DESIGN", "Design", null,
                null, null, "{}");

        var originator = organizationService.create("Prime Mechanical Documents LLC", "Prime Mechanical Docs");

        numberService.defineSeries(project.id(), "PRIME_MEP_SD", "SHOP_DRAWING", "AUR-MEP-SD", 4, "-");
        numberService.defineSeries(project.id(), "COORD_SD", "SHOP_DRAWING", "AUR-COORD-SD", 3, "-");
        numberService.defineSeries(project.id(), "TENDER_RFI", "RFI", "AUR-RFI", 3, "-");

        String shopDrawingMetadata = "{\"discipline\":\"MEP\",\"package\":\"CHW\",\"location\":\"ZONE-B\",\"issuePurpose\":\"CONSTRUCTION\"}";
        var shopDrawing = documentService.create(
                project.id(), mep.id(), originator.id(), null, "PRIME_MEP_SD",
                "SHOP_DRAWING", "CHW Routing Shop Drawing", "Zone B routing",
                "PROJECT", shopDrawingMetadata);
        assertEquals("AUR-MEP-SD-0001", shopDrawing.documentNumber());
        assertEquals("GENERATED", shopDrawing.numberSource());
        assertEquals("PRIME_MEP_SD", shopDrawing.numberSeriesCode());
        assertEquals(mep.id(), shopDrawing.primaryScopeId());
        assertEquals(shopDrawingMetadata, shopDrawing.metadataJson());
        assertEquals(0, shopDrawing.currentRevisionSequence());
        assertNull(shopDrawing.currentRevisionCode());

        var projectWideDocument = documentService.create(
                project.id(), null, originator.id(), null, "COORD_SD",
                "SHOP_DRAWING", "Project-wide Coordination Register", null,
                null, "{\"registerType\":\"COORDINATION\"}");
        assertEquals("AUR-COORD-SD-001", projectWideDocument.documentNumber());
        assertEquals("COORD_SD", projectWideDocument.numberSeriesCode());
        assertNull(projectWideDocument.primaryScopeId());

        var rfi = documentService.create(
                project.id(), tender.id(), originator.id(), null, "TENDER_RFI",
                "RFI", "Tender Clarification RFI", null,
                null, "{\"tenderPackage\":\"TP-01\"}");
        assertEquals("AUR-RFI-001", rfi.documentNumber());

        var externalCertificate = documentService.create(
                project.id(), null, originator.id(), "vendor/cert/77", null,
                "VENDOR_CERTIFICATE", "Vendor Certificate", null,
                null, null);
        assertEquals("VENDOR/CERT/77", externalCertificate.documentNumber());
        assertEquals("EXTERNAL", externalCertificate.numberSource());
        assertNull(externalCertificate.numberSeriesCode());
        assertEquals("{}", externalCertificate.metadataJson());

        String firstSha = "a".repeat(64);
        var revisionA = documentService.addRevision(
                shopDrawing.id(), "A", "Initial coordination issue",
                "object://project-control/documents/rev-a", firstSha,
                "chw-routing-a.pdf", "application/pdf", 2048L);
        var revisionB = documentService.addRevision(
                shopDrawing.id(), "B", "Updated after coordination",
                "object://project-control/documents/rev-b", "b".repeat(64),
                "chw-routing-b.pdf", "application/pdf", 3072L);

        assertEquals(1, revisionA.sequenceNumber());
        assertEquals("A", revisionA.revisionCode());
        assertEquals(2, revisionB.sequenceNumber());
        assertEquals("B", revisionB.revisionCode());
        assertEquals(firstSha, revisionA.contentSha256());

        var refreshed = documentService.get(shopDrawing.id());
        assertEquals(2, refreshed.currentRevisionSequence());
        assertEquals("B", refreshed.currentRevisionCode());
        assertEquals(2, documentService.listRevisions(shopDrawing.id()).size());

        assertThrows(ResponseStatusException.class, () -> documentService.create(
                project.id(), foreignScope.id(), originator.id(), "BAD-SCOPE-DOC", null,
                "GENERAL", "Invalid cross-project scope", null,
                null, null));

        assertThrows(ResponseStatusException.class, () -> documentService.create(
                project.id(), null, originator.id(), null, "TENDER_RFI",
                "SHOP_DRAWING", "Wrong numbering series", null,
                null, null));

        assertTrue(numberService.listSeries(project.id()).stream()
                .anyMatch(series -> series.seriesCode().equals("PRIME_MEP_SD")
                        && series.documentType().equals("SHOP_DRAWING")
                        && series.nextNumber() == 2));
        assertEquals(2, numberService.listSeries(project.id()).stream()
                .filter(series -> series.documentType().equals("SHOP_DRAWING"))
                .count());
    }
}
