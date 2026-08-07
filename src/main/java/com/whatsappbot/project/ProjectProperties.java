package com.whatsappbot.project;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Defaults applied when a project or a numbering series does not state its own.
 *
 * <p>These are commercial conventions rather than product rules — retention rates and currencies
 * differ by contract and by market — so they are configuration, overridable per deployment.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.project")
public class ProjectProperties {

    /** Currency assigned to a new project when none is given. */
    private String defaultCurrency = "AED";

    /** Retention rate applied when a project does not set one. UAE contracts commonly use 5–10%. */
    private BigDecimal defaultRetentionPercent = new BigDecimal("10.00");

    /** Digits a reference number is padded to, e.g. 4 gives RFI-0042. */
    private int defaultNumberPadding = 4;

    /** Separator between the parts of a document reference. */
    private String referenceSeparator = "-";

    /** Status a project starts in. */
    private String defaultStatus = "ACTIVE";
}
