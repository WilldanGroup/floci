package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.core.common.Base64Url;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An OIDC issuer served from a test: discovery and a key set over https on a free port, under a
 * self-signed certificate, signing RS256 tokens and counting the requests that reach it, for the
 * classes that test what STS reads from a declared issuer.
 */
public final class SelfSignedOidcIssuer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISCOVERY = "/.well-known/openid-configuration";
    private static final String KEYS = "/keys";

    private final HttpsServer server;
    private final X509Certificate certificate;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, KeyPair> servedKeys = new LinkedHashMap<>();
    private volatile String signingKid;
    private volatile String declaredIssuer;
    private volatile String jwksUri;
    private volatile int discoveryStatus = 200;
    private volatile String discoveryLocation;

    private SelfSignedOidcIssuer(HttpsServer server, X509Certificate certificate) {
        this.server = server;
        this.certificate = certificate;
    }

    /** An issuer whose certificate names the host it is reached at. */
    public static SelfSignedOidcIssuer start() throws Exception {
        return start("localhost");
    }

    /** An issuer whose certificate names {@code certificateHost}, reached at {@code localhost}. */
    public static SelfSignedOidcIssuer start(String certificateHost) throws Exception {
        KeyPair tlsKey = newKey();
        X509Certificate certificate = selfSigned(tlsKey, certificateHost);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("tls", tlsKey.getPrivate(), new char[0], new X509Certificate[]{certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        SelfSignedOidcIssuer issuer = new SelfSignedOidcIssuer(server, certificate);
        issuer.declaredIssuer = issuer.url();
        issuer.jwksUri = issuer.url() + KEYS;
        issuer.addKey("key-1");
        server.createContext("/", issuer::handle);
        server.start();
        return issuer;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals(DISCOVERY) && discoveryLocation != null) {
                exchange.getResponseHeaders().add("Location", discoveryLocation);
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals(DISCOVERY) && discoveryStatus == 200) {
                respond(exchange, Map.of("issuer", declaredIssuer, "jwks_uri", jwksUri));
            } else if (path.equals(KEYS)) {
                respond(exchange, Map.of("keys", jwks()));
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void respond(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private List<Map<String, String>> jwks() {
        List<Map<String, String>> keys = new ArrayList<>();
        servedKeys.forEach((kid, pair) -> {
            RSAPublicKey key = (RSAPublicKey) pair.getPublic();
            keys.add(Map.of("kty", "RSA", "use", "sig", "alg", "RS256", "kid", kid,
                    "n", unsigned(key.getModulus()), "e", unsigned(key.getPublicExponent())));
        });
        return keys;
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int start = bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Arrays.copyOfRange(bytes, start, bytes.length));
    }

    /** The issuer's URL, which a provider declares and a token names. */
    public String url() {
        return "https://localhost:" + server.getAddress().getPort();
    }

    /** {@link #url()} without its scheme, as a provider's ARN and condition keys spell it. */
    public String hostAndPort() {
        return "localhost:" + server.getAddress().getPort();
    }

    /** The hex SHA-1 of the certificate this issuer serves. */
    public String thumbprint() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
    }

    /** How many requests have reached this issuer. */
    public int requests() {
        return requests.get();
    }

    /** Adds a key to the set and makes it the one tokens are signed with. */
    public void addKey(String kid) throws Exception {
        servedKeys.put(kid, newKey());
        signingKid = kid;
    }

    /** Replaces the key set with one new key. */
    public void rotateTo(String kid) throws Exception {
        servedKeys.clear();
        addKey(kid);
    }

    /** Has the discovery document name another issuer. */
    public void declareIssuerAs(String other) {
        declaredIssuer = other;
    }

    /** Has the discovery document name another key set. */
    public void pointKeysAt(String uri) {
        jwksUri = uri;
    }

    /** Has the discovery document answer 404. */
    public void hideDiscovery() {
        discoveryStatus = 404;
    }

    /** Has the discovery document answer a redirect. */
    public void redirectDiscoveryTo(String location) {
        discoveryLocation = location;
    }

    /** The claims a good token carries: this issuer, a subject, {@code audience}, an hour to live. */
    public Map<String, Object> claims(String audience) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", url());
        claims.put("sub", "subject-1");
        claims.put("aud", audience);
        claims.put("exp", Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond());
        return claims;
    }

    /** A good token for {@code audience}, signed with the current key. */
    public String token(String audience) throws Exception {
        return signed(claims(audience));
    }

    /** {@code claims}, signed with the current key under its {@code kid}. */
    public String signed(Map<String, Object> claims) throws Exception {
        return signed(claims, signingKid);
    }

    /** {@code claims}, signed with the current key, naming {@code kid} or, when it is null, none. */
    public String signed(Map<String, Object> claims, String kid) throws Exception {
        return signed(claims, kid, servedKeys.get(signingKid).getPrivate());
    }

    /** {@code claims}, signed with {@code key}, naming {@code kid} or, when it is null, none. */
    public static String signed(Map<String, Object> claims, String kid, PrivateKey key) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        if (kid != null) {
            header.put("kid", kid);
        }
        String signingInput = segment(header) + "." + segment(claims);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    /** {@code claims} with {@code alg: none} and no signature. */
    public static String unsigned(Map<String, Object> claims) throws Exception {
        return segment(Map.of("alg", "none")) + "." + segment(claims) + ".";
    }

    /** {@code claims} under a header naming {@code alg}, with a signature that means nothing. */
    public static String underAlgorithm(String alg, Map<String, Object> claims) throws Exception {
        return segment(Map.of("alg", alg)) + "." + segment(claims) + ".c2lnbmF0dXJl";
    }

    private static String segment(Object value) throws Exception {
        return Base64Url.encode(JSON.writeValueAsString(value));
    }

    /** A new RSA key pair, for a signature the issuer's set does not hold. */
    public static KeyPair newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair key, String host) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=" + host);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)), name, key.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, host)));
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
