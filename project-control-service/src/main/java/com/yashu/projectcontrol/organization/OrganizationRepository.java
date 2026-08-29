package com.yashu.projectcontrol.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
