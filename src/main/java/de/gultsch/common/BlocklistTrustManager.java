package de.gultsch.common;

import android.annotation.SuppressLint;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/** Rejects chains that contain an intermediate CA whose key is on the blocklist. */
@SuppressLint("CustomX509TrustManager")
final class BlocklistTrustManager implements X509TrustManager {

    // SHA-256 of the SubjectPublicKeyInfo. Cross-signed variants share a key.
    private static final ImmutableSet<String> BLOCKED_SPKI_SHA256 =
            ImmutableSet.of(
                    // eMudhra, revoked 2026-10-18 for CA compromise
                    // EM DV TLS CA - G3B
                    "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105",
                    // EM OV TLS CA - G3B
                    "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b",
                    // EM EV TLS CA - G3B
                    "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41",
                    // EM DV TLS CA - G3C
                    "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f",
                    // EM OV TLS CA - G3C
                    "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8",
                    // EM EV TLS CA - G3C
                    "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0",
                    // EM OV CS CA - G3B
                    "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6",
                    // EM OV CS CA - G3C
                    "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30");

    private final X509TrustManager delegate;

    BlocklistTrustManager(final X509TrustManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType)
            throws CertificateException {
        checkBlocklist(chain);
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType)
            throws CertificateException {
        checkBlocklist(chain);
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    private static void checkBlocklist(final X509Certificate[] chain) throws CertificateException {
        for (final X509Certificate certificate : chain) {
            final var spki = certificate.getPublicKey().getEncoded();
            if (BLOCKED_SPKI_SHA256.contains(Hashing.sha256().hashBytes(spki).toString())) {
                throw new CertificateException(
                        new CertPathValidatorException(
                                "Distrusted CA in chain: "
                                        + certificate.getSubjectX500Principal().getName()));
            }
        }
    }
}
