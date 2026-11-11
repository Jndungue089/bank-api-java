package com.sb24.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sb24.api.domain.IdempotencyRecord;
import com.sb24.api.repo.IdempotencyRepository;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Makes retries of money-moving calls safe. Same key + same payload replays the stored result; same key with a
 * different payload is rejected. Must run inside the caller's transaction so the record commits atomically with the
 * ledger changes (the unique (user, key) constraint also protects against two concurrent duplicates).
 */
@Service
public class IdempotencyService {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,80}$");
    private final IdempotencyRepository repo;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public record Result<T>(T body, boolean replayed) {}

    public <T> Result<T> execute(UUID userId, String key, String requestHash, Class<T> type, Supplier<T> action) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Cabeçalho Idempotency-Key obrigatório (8-80 caracteres alfanuméricos, _ ou -).");
        }
        var existing = repo.findByUserIdAndKey(userId, key);
        try {
            if (existing.isPresent()) {
                if (!existing.get().getRequestHash().equals(requestHash)) {
                    throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "Esta Idempotency-Key já foi usada com outro pedido.");
                }
                return new Result<>(mapper.readValue(existing.get().getResponseBody(), type), true);
            }
            T result = action.get();
            IdempotencyRecord rec = new IdempotencyRecord();
            rec.setUserId(userId);
            rec.setKey(key);
            rec.setRequestHash(requestHash);
            rec.setResponseBody(mapper.writeValueAsString(result));
            repo.saveAndFlush(rec);
            return new Result<>(result, false);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
