package dev.healstore.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;

class ContainerRestartTest {
    @Test void readinessUsesTheNewPortAfterRestart() throws Exception {
        var oldEndpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var newEndpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var oldRequests = new AtomicInteger();
        var newRequests = new AtomicInteger();
        oldEndpoint.createContext("/api/v1/capabilities", exchange -> {
            oldRequests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        newEndpoint.createContext("/api/v1/capabilities", exchange -> {
            newRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            oldEndpoint.start();
            newEndpoint.start();
            var inspection = new AtomicReference<>(inspection(oldEndpoint.getAddress().getPort()));
            var node = spy(new InfrastructureIT.ApplicationContainer(CompletableFuture.completedFuture("test-node")));
            doReturn("test-node").when(node).getContainerId();
            doReturn("127.0.0.1").when(node).getHost();
            doAnswer(call -> inspection.get()).when(node).getCurrentContainerInfo();
            node.withExposedPorts(8443);

            assertEquals(oldEndpoint.getAddress().getPort(), node.getMappedPort(8443));
            inspection.set(inspection(newEndpoint.getAddress().getPort()));
            Wait.forHttp("/api/v1/capabilities").forPort(8443)
                .withStartupTimeout(Duration.ofSeconds(2)).waitUntilReady(node);

            assertEquals(newEndpoint.getAddress().getPort(), node.getMappedPort(8443));
            assertEquals(0, oldRequests.get());
            assertTrue(newRequests.get() > 0);
        } finally {
            oldEndpoint.stop(0);
            newEndpoint.stop(0);
        }
    }

    private static InspectContainerResponse inspection(int hostPort) {
        var response = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        var ports = new Ports();
        ports.bind(ExposedPort.tcp(8443), Ports.Binding.bindPort(hostPort));
        when(response.getName()).thenReturn("test-node");
        when(response.getNetworkSettings().getPorts()).thenReturn(ports);
        return response;
    }
}
