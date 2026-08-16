package com.whatsappbot.delivery;

import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectDeliveryService {

    private final ProjectService projectService;
    private final ProjectDeliveryRepository repository;

    @Transactional(readOnly = true)
    public PortfolioView portfolio(UUID tenantId, UUID userId) {
        List<ProjectCardView> projects = projectService.list(tenantId, userId, "ACTIVE").stream()
                .map(p -> toProjectCard(tenantId, p))
                .toList();
        BigDecimal totalContract = projects.stream().map(ProjectCardView::contractValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalActual = projects.stream().map(ProjectCardView::actualCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int openItems = projects.stream().mapToInt(ProjectCardView::openWorkItems).sum();
        int blockedItems = projects.stream().mapToInt(ProjectCardView::blockedWorkItems).sum();
        int overdueDocuments = projects.stream().mapToInt(ProjectCardView::overdueDocuments).sum();
        return new PortfolioView(repository.accountName(tenantId), projects.size(), totalContract,
                totalActual, openItems, blockedItems, overdueDocuments, projects);
    }

    @Transactional(readOnly = true)
    public ProjectDetailView project(UUID tenantId, UUID userId, UUID projectId) {
        ProjectEntity project = projectService.get(tenantId, userId, projectId);
        ProjectDeliveryRepository.ProjectMetrics metrics = repository.metrics(tenantId, projectId);
        List<ParticipantView> participants = repository.participants(tenantId, projectId).stream()
                .map(p -> new ParticipantView(p.id(), p.organizationId(), p.organizationName(), p.organizationCode(),
                        p.partyRole(), p.parentParticipantId(), p.staffCount()))
                .toList();

        List<StageView> stages = repository.stages(tenantId, projectId).stream()
                .map(stage -> {
                    List<WorkPackageView> packages = repository.packages(tenantId, projectId, stage.id()).stream()
                            .map(pkg -> toPackage(tenantId, projectId, pkg))
                            .toList();
                    BigDecimal budget = packages.stream().map(WorkPackageView::budgetAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal actual = packages.stream().map(WorkPackageView::actualCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    int open = packages.stream().mapToInt(WorkPackageView::openWorkItems).sum();
                    int blocked = packages.stream().mapToInt(WorkPackageView::blockedWorkItems).sum();
                    return new StageView(stage.id(), stage.stageCode(), stage.name(), stage.stageType(),
                            stage.sequenceNo(), stage.status(), stage.progressPercent(), stage.plannedStart(),
                            stage.plannedEnd(), stage.actualStart(), stage.actualEnd(), budget, actual,
                            open, blocked, packages);
                }).toList();

        DeliveryKpis kpis = new DeliveryKpis(metrics.progressPercent(), metrics.actualCost(),
                metrics.openWorkItems(), metrics.blockedWorkItems(), metrics.overdueDocuments(),
                metrics.pendingApprovals(), metrics.totalHours(), metrics.stageCount(), metrics.completedStages());

        return new ProjectDetailView(project.getId(), project.getProjectCode(), project.getName(),
                project.getDescription(), project.getStatus(), project.getCurrency(), nz(project.getContractValue()),
                project.getStartDate(), project.getEndDate(), kpis, participants, stages);
    }

    private ProjectCardView toProjectCard(UUID tenantId, ProjectEntity project) {
        ProjectDeliveryRepository.ProjectMetrics m = repository.metrics(tenantId, project.getId());
        return new ProjectCardView(project.getId(), project.getProjectCode(), project.getName(), project.getStatus(),
                project.getCurrency(), nz(project.getContractValue()), project.getStartDate(), project.getEndDate(),
                m.progressPercent(), m.actualCost(), m.openWorkItems(), m.blockedWorkItems(), m.overdueDocuments(),
                m.pendingApprovals(), m.participantCount(), m.stageCount(), m.completedStages());
    }

    private WorkPackageView toPackage(UUID tenantId, UUID projectId,
                                      ProjectDeliveryRepository.PackageRow pkg) {
        List<WorkItemView> items = repository.workItems(tenantId, projectId, pkg.id()).stream()
                .map(item -> new WorkItemView(item.id(), item.itemCode(), item.name(), item.workType(), item.status(),
                        item.priority(), item.progressPercent(), item.responsibleOrganizationId(),
                        item.responsibleOrganizationName(), item.budgetLineId(), item.budgetAmount(), item.actualCost(),
                        item.totalHours(), item.documentCount(), item.pendingDocumentCount(), item.blockedReason(),
                        item.plannedStart(), item.plannedEnd(), item.actualStart(), item.actualEnd(),
                        repository.assignments(tenantId, item.id()).stream()
                                .map(a -> new AssignmentView(a.userId(), a.fullName(), a.jobTitle(), a.department(),
                                        a.accessRole(), a.organizationName(), a.responsibility())).toList(),
                        repository.documents(tenantId, item.id()).stream()
                                .map(d -> new WorkDocumentView(d.id(), d.documentCode(), d.title(), d.docType(),
                                        d.status(), d.revisionCode(), d.reviewOutcome(), d.dueAt(), d.approvedValue()))
                                .toList()))
                .toList();
        BigDecimal budget = items.stream().map(WorkItemView::budgetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = items.stream().map(WorkItemView::actualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hours = items.stream().map(WorkItemView::totalHours).reduce(BigDecimal.ZERO, BigDecimal::add);
        int open = (int) items.stream().filter(i -> !isClosed(i.status())).count();
        int blocked = (int) items.stream().filter(i -> "BLOCKED".equals(i.status())).count();
        BigDecimal progress = items.isEmpty() ? BigDecimal.ZERO : items.stream().map(WorkItemView::progressPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(items.size()), 1, RoundingMode.HALF_UP);
        return new WorkPackageView(pkg.id(), pkg.packageCode(), pkg.name(), pkg.discipline(), pkg.status(),
                progress, budget, actual, hours, open, blocked, items);
    }

    private static boolean isClosed(String status) {
        return "COMPLETED".equals(status) || "CLOSED".equals(status) || "CANCELLED".equals(status);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record PortfolioView(String accountName, int activeProjects, BigDecimal totalContractValue,
                                BigDecimal totalActualCost, int openWorkItems, int blockedWorkItems,
                                int overdueDocuments, List<ProjectCardView> projects) {}

    public record ProjectCardView(UUID id, String projectCode, String name, String status, String currency,
                                  BigDecimal contractValue, LocalDate startDate, LocalDate endDate,
                                  BigDecimal progressPercent, BigDecimal actualCost, int openWorkItems,
                                  int blockedWorkItems, int overdueDocuments, int pendingApprovals,
                                  int participantCount, int stageCount, int completedStages) {}

    public record ProjectDetailView(UUID id, String projectCode, String name, String description, String status,
                                    String currency, BigDecimal contractValue, LocalDate startDate, LocalDate endDate,
                                    DeliveryKpis kpis, List<ParticipantView> participants, List<StageView> stages) {}

    public record DeliveryKpis(BigDecimal progressPercent, BigDecimal actualCost, int openWorkItems,
                               int blockedWorkItems, int overdueDocuments, int pendingApprovals,
                               BigDecimal totalHours, int stageCount, int completedStages) {}

    public record ParticipantView(UUID id, UUID organizationId, String organizationName, String organizationCode,
                                  String partyRole, UUID parentParticipantId, int staffCount) {}

    public record StageView(UUID id, String stageCode, String name, String stageType, int sequenceNo,
                            String status, BigDecimal progressPercent, LocalDate plannedStart, LocalDate plannedEnd,
                            LocalDate actualStart, LocalDate actualEnd, BigDecimal budgetAmount,
                            BigDecimal actualCost, int openWorkItems, int blockedWorkItems,
                            List<WorkPackageView> workPackages) {}

    public record WorkPackageView(UUID id, String packageCode, String name, String discipline, String status,
                                  BigDecimal progressPercent, BigDecimal budgetAmount, BigDecimal actualCost,
                                  BigDecimal totalHours, int openWorkItems, int blockedWorkItems,
                                  List<WorkItemView> workItems) {}

    public record WorkItemView(UUID id, String itemCode, String name, String workType, String status,
                               String priority, BigDecimal progressPercent, UUID responsibleOrganizationId,
                               String responsibleOrganizationName, UUID budgetLineId, BigDecimal budgetAmount,
                               BigDecimal actualCost, BigDecimal totalHours, int documentCount,
                               int pendingDocumentCount, String blockedReason, LocalDate plannedStart,
                               LocalDate plannedEnd, LocalDate actualStart, LocalDate actualEnd,
                               List<AssignmentView> assignments, List<WorkDocumentView> documents) {}

    public record AssignmentView(UUID userId, String fullName, String jobTitle, String department,
                                 String accessRole, String organizationName, String responsibility) {}

    public record WorkDocumentView(UUID id, String documentCode, String title, String docType, String status,
                                   String revisionCode, String reviewOutcome, LocalDateTime dueAt,
                                   BigDecimal approvedValue) {}
}
