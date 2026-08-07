package com.whatsappbot.project;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues the running reference numbers that project correspondence is tracked by.
 *
 * <p>Every instrument type on a project keeps its own series, so RFIs and payment certificates
 * number independently and a gap in one is not a gap in another.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    private final DocumentNumberSeriesRepository seriesRepository;
    private final TenantRepository tenantRepository;
    private final ProjectProperties properties;

    @Transactional
    public DocumentNumberSeriesEntity defineSeries(UUID tenantId, UUID projectId,
                                                    DefineSeriesRequest req) {
        String docType = normaliseDocType(req.docType());

        Optional<DocumentNumberSeriesEntity> existing =
                seriesRepository.findByTenantIdAndProjectIdAndDocType(tenantId, projectId, docType);

        DocumentNumberSeriesEntity series = existing.orElseGet(() -> {
            TenantEntity tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
            DocumentNumberSeriesEntity created = new DocumentNumberSeriesEntity();
            created.setTenant(tenant);
            created.setProjectId(projectId);
            created.setDocType(docType);
            return created;
        });

        series.setPrefix(req.prefix() != null && !req.prefix().isBlank()
                ? req.prefix().trim().toUpperCase()
                : docType);
        if (req.padding() != null) {
            series.setPadding(req.padding());
        } else if (existing.isEmpty()) {
            series.setPadding(properties.getDefaultNumberPadding());
        }
        if (req.responseDays() != null) {
            series.setResponseDays(req.responseDays());
        }
        // next_number is deliberately not settable here: rewinding a live series would reissue
        // references that already exist on issued documents.

        return seriesRepository.save(series);
    }

    @Transactional(readOnly = true)
    public List<DocumentNumberSeriesEntity> listSeries(UUID tenantId, UUID projectId) {
        return seriesRepository.findAllByTenantIdAndProjectId(tenantId, projectId);
    }

    /**
     * Takes the next reference for a type, e.g. {@code ACME-RFI-0042}.
     *
     * <p>Runs in its own transaction so the number is committed as soon as it is taken. If the
     * caller's work then fails, the reference is spent rather than reused — a gap in the sequence
     * is harmless, whereas two documents sharing a reference is not.
     *
     * @param orgCode the issuing company's code, or null to omit it from the reference
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextReference(UUID tenantId, UUID projectId, String docType, String orgCode) {
        String type = normaliseDocType(docType);

        DocumentNumberSeriesEntity series = seriesRepository
                .lockForUpdate(tenantId, projectId, type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No numbering series defined for '" + type + "' on this project"));

        int number = series.getNextNumber();
        series.setNextNumber(number + 1);
        series.setUpdatedAt(LocalDateTime.now());
        seriesRepository.save(series);

        String separator = properties.getReferenceSeparator();
        String padded = String.format("%0" + series.getPadding() + "d", number);

        StringBuilder reference = new StringBuilder();
        if (orgCode != null && !orgCode.isBlank()) {
            reference.append(orgCode.trim().toUpperCase()).append(separator);
        }
        reference.append(series.getPrefix()).append(separator).append(padded);
        return reference.toString();
    }

    /** The contractual reply window for a type, when the series defines one. */
    @Transactional(readOnly = true)
    public Optional<Integer> responseDaysFor(UUID tenantId, UUID projectId, String docType) {
        return seriesRepository
                .findByTenantIdAndProjectIdAndDocType(tenantId, projectId, normaliseDocType(docType))
                .map(DocumentNumberSeriesEntity::getResponseDays);
    }

    private String normaliseDocType(String docType) {
        if (docType == null || docType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docType is required");
        }
        return docType.trim().toUpperCase();
    }

    public record DefineSeriesRequest(String docType, String prefix, Integer padding,
                                       Integer responseDays) {}
}
