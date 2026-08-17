package com.whatsappbot.features;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "feature_catalog")
public class FeatureCatalogEntity {

    @Id
    @Column(name = "feature_code", length = 100)
    private String featureCode;

    @Column(name = "module", nullable = false, length = 50)
    private String module;

    @Column(name = "nav_section", length = 100)
    private String navSection;

    @Column(name = "nav_label", nullable = false, length = 150)
    private String navLabel;

    @Column(name = "nav_icon", length = 50)
    private String navIcon;

    @Column(name = "route", length = 200)
    private String route;

    @Column(name = "min_role", nullable = false, length = 50)
    private String minRole;

    @Column(name = "is_core", nullable = false)
    private boolean core;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
