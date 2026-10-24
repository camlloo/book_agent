package com.bookagent.backend.service;

import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Derived conversational intent. Original messages remain the sole conversation record. */
@Service @RequiredArgsConstructor @Slf4j
public class ConversationMemory {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiClient ai;
    public record Prepared(JsonNode memory, long covered, String basis, boolean changed, boolean failed) {}

    public List<JsonNode> eligible(JsonNode detail) {
        var result = new ArrayList<JsonNode>();
        for (var message : detail.path("messages"))
            if ("SUCCEEDED".equals(message.path("status").asText()) && !message.path("redacted").asBoolean()) result.add(message);
        return result;
    }

    public String fingerprint(List<JsonNode> messages, long covered, String scope) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            for (var message : messages) if (message.path("id").asLong() <= covered) {
                digest.update(json.writeValueAsBytes(List.of(message.path("id").asLong(), message.path("role").asText(), message.path("content").asText())));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) { throw new IllegalStateException("Cannot identify conversation context", error); }
    }

    public Prepared prepare(long id, List<JsonNode> messages, String scope) {
        JsonNode memory = json.createObjectNode(); long covered = 0;
        var cached = jdbc.queryForList("SELECT covered_message_id,memory_json,basis_hash FROM knowledge_conversation_memory WHERE conversation_id=?", id);
        if (!cached.isEmpty()) {
            var row = cached.getFirst(); long old = ((Number) row.get("covered_message_id")).longValue();
            if (Objects.equals(row.get("basis_hash"), fingerprint(messages, old, scope))) {
                try { memory = json.readTree(row.get("memory_json").toString()); covered = old; }
                catch (java.io.IOException ignored) { /* Rebuild derived memory from visible messages. */ }
            }
        }
        int cutoff = Math.max(0, messages.size() - 8);
        List<JsonNode> older = messages.subList(0, cutoff);
        long previousCovered = covered;
        var pending = older.stream().filter(message -> message.path("id").asLong() > previousCovered).toList();
        if (pending.size() < 4) return new Prepared(memory, covered, fingerprint(messages, covered, scope), !cached.isEmpty() && covered == 0, false);
        long through = older.getLast().path("id").asLong();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("previous", memory);
        payload.put("messages", pending.stream().map(message -> Map.of("role", message.path("role").asText(),
            "content", message.path("content").asText().substring(0, Math.min(4000, message.path("content").asText().length())))).toList());
        try {
            var result = ai.call("/internal/context/compact", payload).path("memory");
            if (!result.isObject() || result.isEmpty() || result.toString().length() > 16000) throw new IllegalStateException("Invalid derived memory");
            return new Prepared(result, through, fingerprint(messages, through, scope), true, false);
        } catch (RuntimeException error) {
            log.warn("Conversation {} intent compression unavailable; retaining recent messages and relevant history", id);
            return new Prepared(memory, covered, fingerprint(messages, covered, scope), !cached.isEmpty() && covered == 0, true);
        }
    }

    /** Called in the answer commit transaction, after scope/history revalidation. */
    public void save(long id, Prepared prepared) {
        if (!prepared.changed()) return;
        jdbc.update("INSERT INTO knowledge_conversation_memory(conversation_id,covered_message_id,memory_json,basis_hash) VALUES(?,?,?,?) " +
            "ON DUPLICATE KEY UPDATE covered_message_id=VALUES(covered_message_id),memory_json=VALUES(memory_json),basis_hash=VALUES(basis_hash)",
            id, prepared.covered(), prepared.memory().toString(), prepared.basis());
    }
}
