package dev.healstore.common.core;

import java.security.cert.X509Certificate;

public final class UnfinishedPeerIdentityPolicy implements PeerIdentityPolicy {
    @Override
    public void authorize(X509Certificate[] certificates, String method, String path) {
        // HANDS_ON: check who the certificate belongs to and what that peer can do.
        throw new HandsOnRequiredException(HandsOnTask.PEER_IDENTITY);
    }
}
