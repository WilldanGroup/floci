package io.github.hectorvent.floci.services.iam;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ThumbprintTrustManagerTest {

    private static KeyPair caKey;
    private static X509Certificate ca;
    private static X509Certificate leaf;
    private static X509Certificate unrelated;

    @BeforeAll
    static void makeCertificates() throws Exception {
        caKey = newKey();
        ca = certificate("CN=ca", "CN=ca", caKey.getPublic(), caKey);
        leaf = certificate("CN=leaf", "CN=ca", newKey().getPublic(), caKey);
        KeyPair otherKey = newKey();
        unrelated = certificate("CN=ca", "CN=ca", otherKey.getPublic(), otherKey);
    }

    @Test
    void aChainTheDelegateAcceptsNeedsNoThumbprint() throws Exception {
        X509TrustManager delegate = mock(X509TrustManager.class);
        X509Certificate[] chain = {leaf, ca};
        doNothing().when(delegate).checkServerTrusted(chain, "RSA");

        assertDoesNotThrow(() -> new ThumbprintTrustManager(delegate, List.of()).checkServerTrusted(chain, "RSA"));
    }

    @Test
    void aRefusedChainHoldingTheLeafsThumbprintIsAccepted() throws Exception {
        assertDoesNotThrow(() -> refusingDefaults(thumbprint(leaf)).checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void aRefusedChainDescendingFromTheCaOfTheThumbprintIsAccepted() throws Exception {
        String upperCase = thumbprint(ca).toUpperCase(Locale.ROOT);

        assertDoesNotThrow(() -> refusingDefaults(upperCase).checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"));
    }

    @Test
    void aRefusedChainWithAnotherThumbprintIsRefused() throws Exception {
        assertThrows(CertificateException.class,
                () -> refusingDefaults(thumbprint(unrelated)).checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"));
    }

    @Test
    void aRefusedChainWithNoThumbprintIsRefused() throws Exception {
        assertThrows(CertificateException.class,
                () -> refusingDefaults().checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void aDeclaredCertificateTheLeafDoesNotDescendFromIsRefused() throws Exception {
        assertThrows(CertificateException.class,
                () -> refusingDefaults(thumbprint(unrelated)).checkServerTrusted(new X509Certificate[]{leaf, unrelated}, "RSA"));
    }

    private static ThumbprintTrustManager refusingDefaults(String... thumbprints) throws Exception {
        X509TrustManager delegate = mock(X509TrustManager.class);
        doThrow(new CertificateException("not trusted")).when(delegate)
                .checkServerTrusted(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        return new ThumbprintTrustManager(delegate, List.of(thumbprints));
    }

    private static String thumbprint(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
    }

    private static KeyPair newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(String subject, String issuer, PublicKey key, KeyPair signer) throws Exception {
        Instant now = Instant.now();
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                new X500Name(issuer), BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)),
                new X500Name(subject), key)
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(signer.getPrivate())));
    }
}
