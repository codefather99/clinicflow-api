package com.clinicflow.web.idempotency;

import com.clinicflow.web.error.IdempotencyKeyMismatchException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Makes a create-type POST safe to submit twice (design.md 6.3).
 *
 * Everything runs in ONE transaction: insert the key row, run the action, store the snapshot, commit.
 * - Two simultaneous submissions with the same key: the second INSERT waits for the first to finish, then finds
 *   the key and replays the stored snapshot. Exactly one booking.
 * - If the action fails (for example SLOT_TAKEN) the transaction rolls back and the key vanishes, so a retry
 *   gets a fresh attempt instead of replaying a failure.
 * - Same key with a different body is refused (IDEMPOTENCY_KEY_MISMATCH).
 * When the real ports are on, their @Transactional methods join this transaction, so booking and key are atomic.
 */
@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** @return the snapshot string: freshly produced by action, or replayed from the first successful call. */
    public String run(String key, String scope, String requestHash, Supplier<String> action) {
        return tx.execute(status -> {
            int inserted = jdbc.update(
                    "INSERT INTO idempotency_key (key, scope, request_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    key, scope, requestHash);
            if (inserted == 1) {
                String snapshot = action.get();
                jdbc.update("UPDATE idempotency_key SET status_code = 201, response_body = ?::jsonb WHERE key = ? AND scope = ?",
                        snapshot, key, scope);
                return snapshot;
            }
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT request_hash, response_body::text AS body FROM idempotency_key WHERE key = ? AND scope = ?",
                    key, scope);
            if (!requestHash.equals(String.valueOf(row.get("request_hash")).trim()))
                throw new IdempotencyKeyMismatchException();
            return String.valueOf(row.get("body"));
        });
    }
}
