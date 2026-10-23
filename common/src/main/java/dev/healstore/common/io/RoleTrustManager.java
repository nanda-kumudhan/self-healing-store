package dev.healstore.common.io;

import dev.healstore.common.core.CertificateIdentity;
import java.net.Socket;
import java.security.cert.*;
import javax.net.ssl.*;

/** Keep normal chain/hostname checks, then check the service role before sending HTTP data. */
final class RoleTrustManager extends X509ExtendedTrustManager {
    private final X509ExtendedTrustManager delegate;
    private final String role;
    RoleTrustManager(X509ExtendedTrustManager delegate, String role) {
        this.delegate = delegate;
        this.role = role;
    }
    private void server(X509Certificate[] chain) throws CertificateException {
        var identity = CertificateIdentity.from(chain);
        boolean allowed = switch (role) {
            case "coordinator" -> identity.coordinator();
            case "node" -> identity.node();
            default -> identity.coordinator() || identity.node();
        };
        if (!allowed)
            throw new CertificateException("Unexpected server certificate role; expected " + role);
    }
    @Override
    public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
        delegate.checkServerTrusted(c, a);
        server(c);
    }
    @Override
    public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
        delegate.checkServerTrusted(c, a, s);
        server(c);
    }
    @Override
    public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
        delegate.checkServerTrusted(c, a, e);
        server(c);
    }
    @Override
    public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
        delegate.checkClientTrusted(c, a);
    }
    @Override
    public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
        delegate.checkClientTrusted(c, a, s);
    }
    @Override
    public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
        delegate.checkClientTrusted(c, a, e);
    }
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
