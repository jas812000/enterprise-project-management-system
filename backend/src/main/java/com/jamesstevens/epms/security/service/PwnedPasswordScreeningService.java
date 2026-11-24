package com.jamesstevens.epms.security.service;

import java.io.Serial;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * HIBP range API client. Never transmits the password or complete SHA-1 digest.
 * Transport failure is distinct from a clean result; callers must not fail open.
 */
public final class PwnedPasswordScreeningService {
    private static final int CONNECTION_TIMEOUT_SECONDS = 3;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;
    private static final int HTTP_OK = 200;
    private static final int SHA1_PREFIX_LENGTH = 5;
    private final RangeTransport transport;
    public enum Result { SAFE, COMPROMISED }
    @FunctionalInterface
    public interface RangeTransport {
        String fetch(String fiveCharacterPrefix) throws Exception;
    }
    public static final class ScreeningUnavailableException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        public ScreeningUnavailableException(Throwable cause) { super("Password screening unavailable", cause); }
    }

    public PwnedPasswordScreeningService(RangeTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    public static PwnedPasswordScreeningService withHttpClient() {
        return new PwnedPasswordScreeningService(prefix -> {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://api.pwnedpasswords.com/range/" + prefix))
                    .header("Add-Padding", "true")
                    .header("User-Agent", "EPMS-Password-Screening")
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .GET().build();
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()) {
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != HTTP_OK) {
                    throw new IllegalStateException("Screening provider returned non-success");
                }
                return response.body();
            }
        });
    }

    public Result screen(String normalizedPassword) {
        Objects.requireNonNull(normalizedPassword, "normalizedPassword");

        try {
            // SHA-1 is required by the HIBP Pwned Passwords range API.
            // This digest is used for breach screening, not password storage.
            // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                    normalizedPassword.getBytes(StandardCharsets.UTF_8));

            String hex = HexFormat.of().withUpperCase().formatHex(digest);
            return screenDigest(hex);

        } catch (NoSuchAlgorithmException exception) {
            throw new ScreeningUnavailableException(exception);
        }
    }

    /** Screens a precomputed SHA-1 digest after decrypting deferred metadata.
     * Only the five-character prefix is sent to the provider.
     */
    public Result screenDigest(String sha1Hex) {
        if (sha1Hex == null || !sha1Hex.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Expected 40-character SHA-1 hex digest");
        }
        String hex = sha1Hex.toUpperCase(Locale.ROOT);
        try {
            String suffix = hex.substring(SHA1_PREFIX_LENGTH);
            String response = transport.fetch(hex.substring(0, SHA1_PREFIX_LENGTH));
            if (response == null) {
                throw new IllegalStateException("Empty screening response");
            }
            Result outcome = Result.SAFE;
            for (String line : response.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split(":", -1);
                if (parts.length != 2 || !parts[0].matches("(?i)[0-9a-f]{35}")
                        || !parts[1].matches("[0-9]+")) {
                    throw new IllegalStateException("Malformed screening response");
                }
                if (parts[0].equalsIgnoreCase(suffix)) {
                    outcome = Long.parseLong(parts[1]) > 0 ? Result.COMPROMISED : Result.SAFE;
                    break;
                }
            }
            return outcome;
        } catch (Exception exception) { // NOPMD - RangeTransport contract permits checked transport failures.
            throw new ScreeningUnavailableException(exception);
        }
    }
}
