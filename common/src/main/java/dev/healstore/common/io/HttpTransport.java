package dev.healstore.common.io;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import javax.net.ssl.*;

/** Reuses connections; responses are capped at a single chunk or metadata record. */
public final class HttpTransport {
    private final java.util.Map<String, HttpClient> clients = new java.util.HashMap<>();
    private final boolean localHttp;
    public HttpTransport(Path keys, Path trust, char[] password, boolean localHttp) throws Exception {
        this.localHttp = localHttp;
        KeyManager[] managers = null;
        X509ExtendedTrustManager trustManager = null;
        if (keys != null) {
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(load(keys, password), password);
            managers = km.getKeyManagers();
            var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(load(trust, password));
            trustManager = (X509ExtendedTrustManager) tm.getTrustManagers()[0];
        }
        for (String role : java.util.List.of("coordinator", "node", "service")) {
            var builder = HttpClient.newBuilder()
                              .connectTimeout(Duration.ofSeconds(2))
                              .followRedirects(HttpClient.Redirect.NEVER);
            if (trustManager != null) {
                var ssl = SSLContext.getInstance("TLS");
                ssl.init(managers, new TrustManager[] {new RoleTrustManager(trustManager, role)}, null);
                builder.sslContext(ssl);
            }
            clients.put(role, builder.build());
        }
    }
    public static HttpTransport service() throws Exception {
        String keys = System.getenv("TLS_KEY_STORE"), trust = System.getenv("TLS_TRUST_STORE");
        if (keys == null || trust == null)
            return new HttpTransport(null, null, null, true);
        char[] pass = System.getenv().getOrDefault("TLS_STORE_PASSWORD", "").toCharArray();
        try {
            return new HttpTransport(file(keys), file(trust), pass, false);
        } finally {
            Arrays.fill(pass, '\0');
        }
    }
    private static Path file(String s) {
        return s.startsWith("file:") ? Path.of(URI.create(s)) : Path.of(s);
    }
    private static KeyStore load(Path p, char[] password) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(p)) {
            store.load(in, password);
        }
        return store;
    }
    public byte[] send(URI origin, String method, String path, byte[] body, String type) throws Exception {
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
            || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
            throw new IllegalArgumentException("Expected an origin URI");
        if (!"https".equals(origin.getScheme())
            && !(localHttp && "http".equals(origin.getScheme())
                && (origin.getHost().equals("localhost") || origin.getHost().equals("127.0.0.1"))))
            throw new IllegalArgumentException("HTTPS is required; loopback HTTP needs --allow-local-http");
        long timeoutSeconds = path.endsWith("/repairs") ? 3600 : type.equals("application/json") ? 60 : 4;
        var req = HttpRequest.newBuilder(origin.resolve(path))
                      .timeout(Duration.ofSeconds(timeoutSeconds))
                      .header("Content-Type", type)
                      .method(method,
                          body == null ? HttpRequest.BodyPublishers.noBody()
                                       : HttpRequest.BodyPublishers.ofByteArray(body))
                      .build();
        String role = path.equals("/api/v1/capabilities") ? "service"
            : path.startsWith("/api/v1/shards/") && !path.contains("/grant") || path.equals("/api/v1/scrubs")
            ? "node"
            : "coordinator";
        var operation = clients.get(role).sendAsync(req, ignored -> new BoundedBody());
        HttpResponse<byte[]> res;
        try {
            res = operation.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            operation.cancel(true);
            throw new HttpTimeoutException("HTTP response body exceeded deadline");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io)
                throw io;
            throw new IOException("HTTP operation failed", e.getCause());
        } catch (InterruptedException e) {
            operation.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
        byte[] bytes = res.body();
        if (res.statusCode() / 100 != 2)
            throw new HttpFailure(
                res.statusCode(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        return bytes;
    }
    public <T> T json(URI origin, String method, String path, Object body, Class<T> type) throws Exception {
        byte[] bytes = send(origin, method, path,
            body == null ? null : Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "application/json");
        return type == Void.class ? null : Json.MAPPER.readValue(bytes, type);
    }
    public static final class HttpFailure extends IOException {
        public final int status;
        public HttpFailure(int status, String message) {
            super("HTTP " + status + ": " + message);
            this.status = status;
        }
    }
}
