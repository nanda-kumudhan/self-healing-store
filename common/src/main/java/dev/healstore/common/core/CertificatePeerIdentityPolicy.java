package dev.healstore.common.core;

import java.security.cert.X509Certificate;

public final class CertificatePeerIdentityPolicy implements PeerIdentityPolicy {
    @Override
    public void authorize(X509Certificate[] chain, String method, String path) {
        var peer = CertificateIdentity.from(chain);
        boolean allowed = false;
        if (path.startsWith("/api/v1/shards/"))
            allowed = peer.client() || peer.coordinator() || (peer.node() && path.endsWith("/grant"));
        else if (path.equals("/api/v1/scrubs"))
            allowed = peer.client() || peer.coordinator();
        else if (path.startsWith("/api/v1/nodes"))
            allowed = method.equals("GET") ? peer.client() || peer.coordinator() : peer.node();
        else if (path.startsWith("/api/v1/objects") || path.equals("/api/v1/uploads"))
            allowed = peer.client();
        if (!allowed)
            throw new SecurityException("Certificate role cannot perform this operation");
    }
}
