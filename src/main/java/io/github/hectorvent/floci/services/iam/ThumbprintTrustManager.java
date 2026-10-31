package io.github.hectorvent.floci.services.iam;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/**
 * Trusts a certificate chain as IAM trusts an OIDC provider's: "If your OIDC IdP relies on a
 * certificate that is not signed by one of these trusted CAs, only then we secure communication
 * using the thumbprints". A chain the delegate refuses is accepted when the hex SHA-1 of one of its
 * certificates, compared without regard to case, is a declared thumbprint, and each certificate below
 * that one is signed by the next, so a chain cannot claim a certificate it does not descend from.
 * That covers AWS's usual thumbprint, the top intermediate CA's, and a self-signed certificate's own.
 *
 * <p>A plain {@link X509TrustManager} on purpose: the JDK wraps one in its own check of the
 * certificate's name against the host, which then applies however the chain was trusted.
 */
final class ThumbprintTrustManager implements X509TrustManager {

    private final X509TrustManager delegate;
    private final List<String> thumbprints;

    ThumbprintTrustManager(X509TrustManager delegate, List<String> thumbprints) {
        this.delegate = delegate;
        this.thumbprints = thumbprints;
    }

    /** Over the trust store the JDK trusts by default. */
    static ThumbprintTrustManager overDefaults(List<String> thumbprints) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return new ThumbprintTrustManager(x509, thumbprints);
            }
        }
        throw new GeneralSecurityException("The JDK has no default X.509 trust manager");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            delegate.checkServerTrusted(chain, authType);
        } catch (CertificateException refused) {
            if (!holdsDeclaredCertificate(chain)) {
                throw refused;
            }
        }
    }

    private boolean holdsDeclaredCertificate(X509Certificate[] chain) {
        try {
            for (int i = 0; i < chain.length; i++) {
                String thumbprint = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-1").digest(chain[i].getEncoded()));
                if (thumbprints.stream().anyMatch(thumbprint::equalsIgnoreCase)) {
                    return true;
                }
                if (i + 1 < chain.length) {
                    chain[i].verify(chain[i + 1].getPublicKey());
                }
            }
            return false;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
