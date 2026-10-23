package dev.healstore.client;

import dev.healstore.common.io.HttpTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

final class CoordinatorTransport {
    static String request(URI base, Path keyStore, Path trustStore, boolean allowLocalHttp, String method,
        String path) throws Exception {
        char[] password = System.getenv().getOrDefault("STORE_TLS_PASSWORD", "").toCharArray();
        try {
            var http = new HttpTransport(
                "https".equals(base.getScheme()) ? keyStore : null, trustStore, password, allowLocalHttp);
            return new String(
                http.send(base, method, path, null, "application/json"), StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
