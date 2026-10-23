package dev.healstore.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.healstore.common.core.*;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;

class IdentityTest {
    private X509Certificate[] peer(String name) throws Exception {
        var cert = mock(X509Certificate.class);
        when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=" + name));
        when(cert.getEncoded()).thenReturn(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new X509Certificate[] {cert};
    }
    @Test
    void rolesAreExactAndAnonymousUnknownOrWrongRolesAreDenied() throws Exception {
        var policy = new CertificatePeerIdentityPolicy();
        assertThrows(SecurityException.class, () -> policy.authorize(null, "GET", "/api/v1/nodes"));
        var node = peer("node-1");
        var client = peer("client");
        var unknown = peer("client-admin");
        policy.authorize(client, "GET", "/api/v1/nodes");
        policy.authorize(node, "POST", "/api/v1/nodes");
        assertThrows(SecurityException.class, () -> policy.authorize(node, "GET", "/api/v1/objects/123"));
        assertThrows(
            SecurityException.class, () -> policy.authorize(client, "POST", "/api/v1/nodes/123/heartbeats"));
        assertThrows(SecurityException.class, () -> policy.authorize(unknown, "GET", "/api/v1/nodes"));
        assertNotEquals(
            CertificateIdentity.from(node).nodeId(), CertificateIdentity.from(peer("node-2")).nodeId());
    }
}
