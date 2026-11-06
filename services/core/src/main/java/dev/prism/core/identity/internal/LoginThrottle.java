package dev.prism.core.identity.internal;

import dev.prism.core.platform.config.PrismProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Progressive sign-in throttling, per account and per client address. Postgres holds the state so
 * that limits apply across instances, and keys are HMACs so that the table holds no personal data.
 */
@Component
class LoginThrottle {

    private static final int FREE_ACCOUNT_FAILURES = 5;
    private static final int FREE_ADDRESS_FAILURES = 30;
    private static final Duration BASE_BLOCK = Duration.ofSeconds(30);
    private static final Duration MAX_BLOCK = Duration.ofMinutes(15);
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    private final JdbcClient jdbc;
    private final SecretKeySpec key;

    LoginThrottle(JdbcClient jdbc, PrismProperties properties) {
        this.jdbc = jdbc;
        byte[] secret = Base64.getDecoder().decode(properties.security().throttleHmacKey());
        this.key = new SecretKeySpec(secret, "HmacSHA256");
    }

    boolean isBlocked(String email, String address) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM core.login_throttle
                                       WHERE throttle_key IN (?, ?) AND blocked_until > now())
                        """)
                .params(accountKey(email), addressKey(address))
                .query(Boolean.class)
                .single();
    }

    @Transactional
    public void recordFailure(String email, String address) {
        increment(accountKey(email), FREE_ACCOUNT_FAILURES);
        increment(addressKey(address), FREE_ADDRESS_FAILURES);
    }

    /** Clears only the account: a success must not erase failures counted against the address. */
    void clear(String email) {
        jdbc.sql("DELETE FROM core.login_throttle WHERE throttle_key = ?").param(accountKey(email)).update();
    }

    private void increment(String throttleKey, int freeFailures) {
        // Failures older than an hour no longer count towards the block.
        int failures = jdbc.sql("""
                        INSERT INTO core.login_throttle AS t (throttle_key, failures, last_failure)
                        VALUES (?, 1, now())
                        ON CONFLICT (throttle_key) DO UPDATE SET
                            failures = CASE WHEN t.last_failure < now() - interval '1 hour' THEN 1
                                            ELSE t.failures + 1 END,
                            last_failure = now()
                        RETURNING failures
                        """)
                .param(throttleKey)
                .query(Integer.class)
                .single();
        if (failures >= freeFailures) {
            Instant blockedUntil = Instant.now().plus(blockDuration(failures - freeFailures));
            jdbc.sql("UPDATE core.login_throttle SET blocked_until = ? WHERE throttle_key = ?")
                    .params(blockedUntil.atOffset(ZoneOffset.UTC), throttleKey)
                    .update();
        }
    }

    static Duration blockDuration(int excessFailures) {
        int doublings = Math.min(excessFailures, 10);
        Duration block = BASE_BLOCK.multipliedBy(1L << doublings);
        return block.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : block;
    }

    private String accountKey(String email) {
        return "account:" + hmac(email.trim().toLowerCase(Locale.ROOT));
    }

    private String addressKey(String address) {
        return "address:" + hmac(address);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", exception);
        }
    }

    /** Keeps the table from growing without bound. Rows this old no longer affect any decision. */
    @Scheduled(fixedDelayString = "PT1H")
    public void purgeStaleRows() {
        jdbc.sql("DELETE FROM core.login_throttle WHERE last_failure < ? AND coalesce(blocked_until, now()) <= now()")
                .param(OffsetDateTime.now(ZoneOffset.UTC).minus(STALE_AFTER))
                .update();
    }
}
