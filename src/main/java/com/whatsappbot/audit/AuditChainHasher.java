package com.whatsappbot.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the linked hashes behind the document and payment audit trails.
 *
 * <p>Shared so both chains commit to the same fields in the same way — a trail that proves a
 * document was approved and one that proves the money was released have to be equally hard to
 * rewrite, and two copies of this logic would eventually stop matching.
 *
 * <p>The digest covers the subject, the event type, the actor, the timestamp and the full
 * payload. Leaving any of those out — as the first version did — lets a historic entry be edited
 * without breaking the chain, which defeats the point of having one.
 */
@Slf4j
@Component
public class AuditChainHasher {

    /** Marks the first event in a chain, which has no predecessor. */
    public static final String GENESIS_HASH = "genesis";

    /** Separates fields in the pre-image so adjacent values cannot run together and collide. */
    private static final String FIELD_SEPARATOR = "|";

    /** Stand-in when a payload cannot be serialised; keeps the chain continuous. */
    private static final String UNSERIALISABLE_PAYLOAD = "<unserialisable>";

    /**
     * Sorts map keys so the same logical payload always serialises identically. A digest that
     * depended on attribute ordering could not be recomputed later to verify the chain.
     */
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /**
     * @param tenantId    owning tenant
     * @param subjectId   the document or payment application the chain belongs to
     * @param eventType   what happened
     * @param actorId     who did it, or null for system-originated events
     * @param occurredAt  when — must be the value stored on the event, not "now"
     * @param payload     the event detail; every field of it is covered by the digest
     * @param previousHash the preceding event's hash, or {@link #GENESIS_HASH}
     */
    public String hash(UUID tenantId, UUID subjectId, String eventType, UUID actorId,
                       LocalDateTime occurredAt, Map<String, Object> payload, String previousHash) {

        String canonicalPayload;
        try {
            canonicalPayload = payload == null ? "" : CANONICAL_JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Never drop the event over a serialisation problem — a missing entry is worse than
            // an imperfect one, and the marker makes the anomaly visible.
            log.warn("Could not canonicalise audit payload for subject {}: {}", subjectId, e.getMessage());
            canonicalPayload = UNSERIALISABLE_PAYLOAD;
        }

        return sha256(String.join(FIELD_SEPARATOR,
                tenantId.toString(),
                subjectId.toString(),
                eventType,
                actorId != null ? actorId.toString() : "",
                occurredAt.toString(),
                canonicalPayload,
                previousHash));
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available", e);
            return "unavailable";
        }
    }
}
