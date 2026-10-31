package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.PublicKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Reads the signing keys of an issuer declared as an IAM OIDC provider: the discovery document at
 * its URL, then the key set the document names, over https and following no redirect.
 *
 * <p>Read on each call rather than cached, so a key the issuer rotates needs no expiry rule here, at
 * the cost of two requests to the issuer for each {@code AssumeRoleWithWebIdentity} that gets this far.
 */
@ApplicationScoped
public class OidcProviderKeyReader {

    private static final Logger LOG = Logger.getLogger(OidcProviderKeyReader.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;

    @Inject
    public OidcProviderKeyReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The key of {@code issuer}'s set that {@code kid} names, or with a null {@code kid} the set's
     * only RSA key. Empty when no such key is held, and when the issuer's keys can't be read: its
     * certificate is not trusted under {@code thumbprints}, a request fails or is not answered, or
     * its discovery document names another issuer.
     */
    public Optional<RSAPublicKey> findKey(String issuer, List<String> thumbprints, String kid) {
        try (HttpClient client = newClient(thumbprints)) {
            JsonNode discovery = readJson(client, issuer + "/.well-known/openid-configuration");
            if (!issuer.equals(discovery.path("issuer").asText(null))) {
                LOG.debugv("The discovery document of {0} names another issuer", issuer);
                return Optional.empty();
            }
            return selectKey(readJson(client, discovery.path("jwks_uri").asText("")), kid);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            LOG.debugv("Could not read the keys of {0}: {1}", issuer, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static HttpClient newClient(List<String> thumbprints) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{ThumbprintTrustManager.overDefaults(thumbprints)}, null);
        return HttpClient.newBuilder()
                .sslContext(context)
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private JsonNode readJson(HttpClient client, String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) {
            throw new IOException("Not an https URL: " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private static Optional<RSAPublicKey> selectKey(JsonNode keySet, String kid) throws GeneralSecurityException {
        List<JsonNode> rsaKeys = new ArrayList<>();
        for (JsonNode key : keySet.path("keys")) {
            if ("RSA".equals(key.path("kty").asText()) && (kid == null || kid.equals(key.path("kid").asText()))) {
                rsaKeys.add(key);
            }
        }
        if (rsaKeys.isEmpty() || (kid == null && rsaKeys.size() > 1)) {
            return Optional.empty();
        }
        JsonNode key = rsaKeys.getFirst();
        return Optional.of(PublicKeys.rsa(unsignedInteger(key.path("n")), unsignedInteger(key.path("e"))));
    }

    private static BigInteger unsignedInteger(JsonNode base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url.asText()));
    }
}
