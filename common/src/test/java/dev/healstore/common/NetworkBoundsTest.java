package dev.healstore.common;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.healstore.common.io.HttpTransport;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class NetworkBoundsTest {
    @Test
    void responseLimitIsEnforcedBeforeBufferingAnArbitraryBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/shards/test", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[3 * 1024 * 1024]);
            } catch (java.io.IOException ignored) { /* The client closes an oversized response. */
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new HttpTransport(null, null, null, true);
            var error = assertThrows(java.io.IOException.class,
                ()
                    -> client.send(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "GET",
                        "/api/v1/shards/test", null, "application/octet-stream"));
            assertTrue(error.getMessage().contains("size limit"));
        } finally {
            server.stop(0);
        }
    }
    @Test
    void aStalledBodyCannotHoldARepairReadIndefinitely() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/shards/test", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write(new byte[] {1});
                exchange.getResponseBody().flush();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new HttpTransport(null, null, null, true);
            assertThrows(HttpTimeoutException.class,
                ()
                    -> client.send(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "GET",
                        "/api/v1/shards/test", null, "application/octet-stream"));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
