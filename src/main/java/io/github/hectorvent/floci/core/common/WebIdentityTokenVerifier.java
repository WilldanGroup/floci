package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Verifies RS256-signed web-identity JWTs against a Floci-known OIDC issuer.
 *
 * <p>Deliberately hand-rolled rather than pulling in a JOSE library: Floci already signs Cognito
 * JWTs with plain {@code java.security} primitives, and this only needs the one algorithm EKS IRSA
 * uses.
 *
 * <p>Two-stage design lets the caller select a trusted key before verification. {@link #peekIssuer}
 * reads the {@code iss} claim <em>without</em> verifying anything. The caller must reject tokens
 * when the issuer does not resolve to a trusted key; {@link #verify} then rejects any verification
 * failure for a known issuer.
 */
@ApplicationScoped
public class WebIdentityTokenVerifier {

    private static final Logger LOG = Logger.getLogger(WebIdentityTokenVerifier.class);

    /** Tolerance for clock drift between Floci and a token minted elsewhere. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    // What real STS answered for each refusal of a token from an issuer Floci does not host, measured
    // on sts.us-east-1.amazonaws.com. The two spaces after the comma are in AWS's answer.
    public static final String NOT_A_JWT =
            "The ID Token provided is not a valid JWT. (You may see this error if you sent an Access Token)";
    public static final String ISSUER_NOT_HTTPS = "Issuer must start with https://";
    public static final String NOT_VALIDATED = "The web identity token provided could not be validated. "
            + "See the AssumeRoleWithWebIdentity documentation for requirements.";
    private static final String UNSIGNED = "We don't accept unsigned JWTs";
    private static final String KEY_NOT_RETRIEVED = "Couldn't retrieve verification key from your identity provider,  "
            + "please reference AssumeRoleWithWebIdentity documentation for requirements";

    private final ObjectMapper objectMapper;

    @Inject
    public WebIdentityTokenVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Thrown when a token claiming a Floci-known issuer fails verification. */
    public static class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a token is past its {@code exp} claim. STS reports this as
     * {@code ExpiredTokenException} rather than {@code InvalidIdentityToken}, so callers can tell
     * a stale token apart from one that can never verify.
     */
    public static class ExpiredTokenException extends InvalidTokenException {
        public ExpiredTokenException(String message) {
            super(message);
        }
    }

    /**
     * Reads the {@code iss} claim without verifying the signature. Returns empty when {@code token}
     * is not a parseable JWT at all — the caller must reject it.
     */
    public Optional<String> peekIssuer(String token) {
        return parseClaims(token).map(claims -> claims.path("iss").asText(null))
                .filter(iss -> iss != null && !iss.isBlank());
    }

    /**
     * Fully verifies {@code token}: RS256 signature against {@code publicKey}, {@code iss} equal to
     * {@code expectedIssuer}, {@code aud} containing {@code requiredAudience}, and {@code exp}/
     * {@code nbf} within {@link #CLOCK_SKEW_SECONDS}.
     *
     * <p>Claim comparisons are exact and case-sensitive, matching AWS treatment of OIDC claims.
     */
    public WebIdentityToken verify(String token, RSAPublicKey publicKey, String expectedIssuer,
                                   String requiredAudience) throws InvalidTokenException {
        // Limit -1 keeps trailing empty segments, so an unsigned "header.payload." token is seen as
        // three parts and rejected by the algorithm check below rather than as malformed.
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("The web identity token is not a well-formed JWT");
        }

        JsonNode header = decodeJson(parts[0])
                .orElseThrow(() -> new InvalidTokenException("The web identity token header is not valid JSON"));
        String alg = header.path("alg").asText("");
        if (!"RS256".equals(alg)) {
            throw new InvalidTokenException("Unsupported web identity token algorithm: "
                    + (alg.isEmpty() ? "none" : alg));
        }

        if (!signatureValid(parts[0] + "." + parts[1], parts[2], publicKey)) {
            throw new InvalidTokenException("The web identity token signature is invalid");
        }

        JsonNode claims = decodeJson(parts[1])
                .orElseThrow(() -> new InvalidTokenException("The web identity token payload is not valid JSON"));

        String issuer = claims.path("iss").asText(null);
        if (issuer == null || !issuer.equals(expectedIssuer)) {
            throw new InvalidTokenException("The web identity token issuer does not match the "
                    + "OIDC provider: " + issuer);
        }

        List<String> audiences = readAudiences(claims);
        if (!audiences.contains(requiredAudience)) {
            throw new InvalidTokenException("The web identity token audience does not include "
                    + requiredAudience);
        }

        verifyValidityWindow(claims);

        String subject = claims.path("sub").asText(null);
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("The web identity token has no subject claim");
        }

        return new WebIdentityToken(issuer, subject, audiences);
    }

    /**
     * Fully verifies {@code token}, a parseable JWT from an issuer declared as an OIDC provider, in
     * the order real STS was measured to check: its expiry, an unsigned token, and the audience
     * against {@code clientIds}, none of which needs the issuer, and only then the key
     * {@code keyByKid} finds for its {@code kid}, which may be null, and the signature under it.
     *
     * <p>An algorithm other than RS256, a token before its {@code nbf}, and one with no {@code exp}
     * or {@code sub} are cases the measurement did not reach, and answer as a bad signature does.
     */
    public WebIdentityToken verifyDeclared(String token, List<String> clientIds,
                                           Function<String, Optional<RSAPublicKey>> keyByKid)
            throws InvalidTokenException {
        String[] parts = token.split("\\.", -1);
        JsonNode header = decodeJson(parts[0]).orElseThrow(() -> new InvalidTokenException(NOT_VALIDATED));
        JsonNode claims = decodeJson(parts[1]).orElseThrow(() -> new InvalidTokenException(NOT_VALIDATED));

        long now = Instant.now().getEpochSecond();
        JsonNode exp = claims.get("exp");
        if (exp != null && exp.isNumber() && now > exp.asLong() + CLOCK_SKEW_SECONDS) {
            throw new ExpiredTokenException("Token expired: current date/time " + now
                    + " must be before the expiration date/time " + exp.asLong());
        }

        String alg = header.path("alg").asText("");
        if ("none".equals(alg)) {
            throw new InvalidTokenException(UNSIGNED);
        }

        List<String> audiences = readAudiences(claims);
        if (audiences.stream().noneMatch(clientIds::contains) || !"RS256".equals(alg)) {
            throw new InvalidTokenException(NOT_VALIDATED);
        }

        RSAPublicKey key = keyByKid.apply(header.path("kid").asText(null))
                .orElseThrow(() -> new InvalidTokenException(KEY_NOT_RETRIEVED));
        if (!signatureValid(parts[0] + "." + parts[1], parts[2], key)) {
            throw new InvalidTokenException(NOT_VALIDATED);
        }

        try {
            verifyValidityWindow(claims);
        } catch (InvalidTokenException e) {
            LOG.debugv("Refusing a declared issuer's token: {0}", e.getMessage());
            throw new InvalidTokenException(NOT_VALIDATED);
        }
        String subject = claims.path("sub").asText(null);
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException(NOT_VALIDATED);
        }

        return new WebIdentityToken(claims.path("iss").asText(), subject, audiences);
    }

    private void verifyValidityWindow(JsonNode claims) throws InvalidTokenException {
        long now = Instant.now().getEpochSecond();

        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.isNumber()) {
            throw new InvalidTokenException("The web identity token has no expiration claim");
        }
        if (now > exp.asLong() + CLOCK_SKEW_SECONDS) {
            throw new ExpiredTokenException("The web identity token has expired");
        }

        JsonNode nbf = claims.get("nbf");
        if (nbf != null && nbf.isNumber() && now + CLOCK_SKEW_SECONDS < nbf.asLong()) {
            throw new InvalidTokenException("The web identity token is not yet valid");
        }
    }

    private boolean signatureValid(String signingInput, String encodedSignature, RSAPublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getUrlDecoder().decode(encodedSignature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A malformed signature segment or a key/algorithm mismatch both mean "not verifiable",
            // which is a rejection rather than a server fault.
            LOG.debugv("Web identity token signature check failed: {0}", e.getMessage());
            return false;
        }
    }

    private List<String> readAudiences(JsonNode claims) {
        List<String> audiences = new ArrayList<>();
        JsonNode aud = claims.get("aud");
        if (aud == null) {
            return audiences;
        }
        if (aud.isTextual()) {
            audiences.add(aud.asText());
        } else if (aud.isArray()) {
            for (JsonNode entry : aud) {
                if (entry.isTextual()) {
                    audiences.add(entry.asText());
                }
            }
        }
        return audiences;
    }

    private Optional<JsonNode> parseClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        // Limit -1 for the same reason as verify(): without it an unsigned "header.payload." token
        // splits into two parts, so its issuer would go unseen and validation could be skipped.
        String[] parts = token.split("\\.", -1);
        return parts.length == 3 ? decodeJson(parts[1]) : Optional.empty();
    }

    private Optional<JsonNode> decodeJson(String base64UrlSegment) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(base64UrlSegment);
            JsonNode node = objectMapper.readTree(decoded);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (IllegalArgumentException | IOException e) {
            // Not base64url, or not JSON. Callers treat an empty result as "unparseable segment",
            // which is a normal outcome when the token came from something other than Floci.
            LOG.debugv("Could not decode web identity token segment: {0}", e.getMessage());
            return Optional.empty();
        }
    }
}
