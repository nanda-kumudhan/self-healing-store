package dev.healstore.common.core;

import java.security.cert.X509Certificate;

/** A trusted certificate still needs permission to do a particular operation. */
public interface PeerIdentityPolicy {
    void authorize(X509Certificate[] certificates, String method, String path);
}
