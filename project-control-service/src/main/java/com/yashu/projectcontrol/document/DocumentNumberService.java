package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentNumberService {

    private final DocumentNumberSeriesRepository repository;
    private final ProjectService projectService;

    public DocumentNumberService(DocumentNumberSeriesRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    @Transactional
    public SeriesView defineSeries(UUID projectId, String documentType, String prefix, Integer padding, String separator) {
        projectService.requireExists(projectId);
        String type = normalizeCode(documentType, "documentType");
        String normalizedPrefix = normalizeCode(prefix == null || prefix.isBlank() ? type : prefix, "prefix");
        int normalizedPadding = padding == null ? 4 : padding;
        if (normalizedPadding < 1 || normalizedPadding > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "padding must be between 1 and 12");
        }
        String normalizedSeparator = separator == null ? "-" : separator.trim();
        if (normalizedSeparator.isEmpty() || normalizedSeparator.length() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "separator must contain 1 to 5 characters");
        }

        DocumentNumberSeries series = repository.findByProjectIdAndDocumentType(projectId, type)
                .map(existing -> {
                    existing.configure(normalizedPrefix, normalizedSeparator, normalizedPadding);
                    return existing;
                })
                .orElseGet(() -> DocumentNumberSeries.create(
                        projectId, type, normalizedPrefix, normalizedSeparator, normalizedPadding));
        return toView(repository.save(series));
    }

    @Transactional(readOnly = true)
    public List<SeriesView> listSeries(UUID projectId) {
        projectService.requireExists(projectId);
        return repository.findByProjectIdOrderByDocumentTypeAsc(projectId).stream()
                .map(DocumentNumberService::toView)
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextReference(UUID projectId, String documentType) {
        String type = normalizeCode(documentType, "documentType");
        DocumentNumberSeries series = repository.lockForUpdate(projectId, type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No document numbering series defined for type '" + type + "' in project " + projectId));
        int number = series.takeNextNumber();
        repository.save(series);
        return series.getPrefix() + series.getSeparator()
                + String.format("%0" + series.getPadding() + "d", number);
    }

    private static String normalizeCode(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static SeriesView toView(DocumentNumberSeries series) {
        return new SeriesView(series.getId(), series.getProjectId(), series.getDocumentType(),
                series.getPrefix(), series.getSeparator(), series.getNextNumber(), series.getPadding());
    }

    public record SeriesView(UUID id, UUID projectId, String documentType, String prefix,
                             String separator, int nextNumber, int padding) {
    }
}
