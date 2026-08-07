package com.whatsappbot.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Teaches Jackson about Hibernate proxies.
 *
 * <p>Most CRM controllers return JPA entities directly, and nearly every entity
 * carries at least one {@code FetchType.LAZY} association (its owning tenant, at
 * minimum). Because {@code spring.jpa.open-in-view} is false, the Hibernate session
 * is already closed by the time Jackson serialises the response, so touching an
 * uninitialised proxy throws {@code LazyInitializationException} — surfacing as a
 * 500 (and, once Spring Security's error dispatch gets involved, an opaque 403).
 *
 * <p>The failure is invisible while a table is empty and appears the moment a tenant
 * has real rows, which makes it a particularly bad one to leave in place.
 *
 * <p>{@code FORCE_LAZY_LOADING} stays disabled on purpose: forcing initialisation
 * would silently issue N+1 queries per response and could serialise a whole tenant
 * object graph into an API payload. Writing {@code null} for a proxy the caller did
 * not ask for is both faster and safer. Endpoints that genuinely need associated
 * data should map to a DTO or fetch it inside a transaction.
 */
@Configuration
public class JacksonHibernateConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        module.disable(Hibernate6Module.Feature.FORCE_LAZY_LOADING);
        return module;
    }
}
