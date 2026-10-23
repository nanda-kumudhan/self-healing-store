package dev.healstore.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class CoordinatorTransport {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    static String request(URI base, Path keyStore, Path trustStore, boolean allowLocalHttp,
                          String method, String path) throws Exception {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER);
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("Coordinator must be an origin URI without credentials, path, query or fragment.");
        }
        if ("https".equals(base.getScheme())) {
            String secret = System.getenv("STORE_TLS_PASSWORD");
            if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Set STORE_TLS_PASSWORD for the development PKCS12 stores.");
            char[] password = secret.toCharArray();
            try {
                var keys = load(keyStore, password);
                var trust = load(trustStore, password);
                var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keys, password);
                var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trust);
                var ssl = SSLContext.getInstance("TLS");
                ssl.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
                builder.sslContext(ssl);
            } finally { Arrays.fill(password, '\0'); }
        } else if (!(allowLocalHttp && "http".equals(base.getScheme())
                && ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost())))) {
            throw new IllegalArgumentException("HTTPS is required; loopback HTTP needs --allow-local-http.");
        }
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").method(method, HttpRequest.BodyPublishers.noBody()).build();
        var response = builder.build().send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var body = response.body()) { bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1); }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Coordinator response exceeded 1 MiB.");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode() + ": " + text);
        return text;
    }

    private static KeyStore load(Path path, char[] password) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) { store.load(input, password); }
        return store;
    }
}
