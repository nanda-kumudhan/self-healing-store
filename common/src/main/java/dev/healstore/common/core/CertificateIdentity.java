package dev.healstore.common.core;

import dev.healstore.common.io.ShardCodec;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.naming.ldap.LdapName;

/** The private development CA reserves these exact CNs. No wildcard role matching. */
public record CertificateIdentity(String name, String fingerprint) {
    public static CertificateIdentity from(X509Certificate[] chain) {
        try {
            if (chain == null || chain.length == 0)
                throw new SecurityException("Client certificate required");
            chain[0].checkValidity();
            var names = new LdapName(chain[0].getSubjectX500Principal().getName())
                            .getRdns()
                            .stream()
                            .filter(r -> r.getType().equalsIgnoreCase("CN"))
                            .map(r -> r.getValue().toString())
                            .toList();
            if (names.size() != 1)
                throw new SecurityException("Expected one certificate CN");
            return new CertificateIdentity(names.getFirst(), ShardCodec.hash(chain[0].getEncoded()));
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Invalid peer certificate", e);
        }
    }
    public boolean client() {
        return name.equals("client");
    }
    public boolean coordinator() {
        return name.equals("coordinator");
    }
    public boolean node() {
        return name.matches("node-[1-9][0-9]*");
    }
    public UUID nodeId() {
        if (!node())
            throw new SecurityException("Node identity required");
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
