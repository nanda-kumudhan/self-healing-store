package dev.healstore.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.Future;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/** Setup checks run with -Pintegration. File recovery needs separate tests. */
class InfrastructureIT {
    @Test void coordinatorMigratesPostgresAndNodeRecoversFromProxyPartitionAndContainerStop() throws Exception {
        Path root = Path.of(System.getProperty("project.root"));
        String version = System.getProperty("store.version");
        try (var network = Network.newNetwork();
             var database = new PostgreSQLContainer<>("postgres:17.6-alpine").withNetwork(network)
                 .withNetworkAliases("postgres").withDatabaseName("healstore");
             var coordinator = application(root, "coordinator", version).withNetwork(network);
             var node = application(root, "storage-node", version).withNetwork(network).withNetworkAliases("node");
             var proxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                 .withNetwork(network)) {
            database.start();
            coordinator.withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/healstore")
                .withEnv("SPRING_DATASOURCE_USERNAME", database.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", database.getPassword()).start();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                 var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT count(*) FROM node_registrations")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            node.start(); proxy.start();
            var route = proxy.getProxy(node, 8443);
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var uri = java.net.URI.create("http://" + proxy.getHost() + ":" + route.getProxyPort() + "/api/v1/capabilities");
            var request = java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build();
            assertEquals(200, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            route.setConnectionCut(true);
            assertThrows(java.io.IOException.class,
                () -> client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()));
            route.setConnectionCut(false);
            assertEquals(200, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            var docker = node.getDockerClient();
            docker.stopContainerCmd(node.getContainerId()).withTimeout(1).exec();
            assertFalse(Boolean.TRUE.equals(docker.inspectContainerCmd(node.getContainerId()).exec().getState().getRunning()));
            docker.startContainerCmd(node.getContainerId()).exec();
            Wait.forHttp("/api/v1/capabilities").forPort(8443).withStartupTimeout(Duration.ofSeconds(90)).waitUntilReady(node);
        }
    }

    private static GenericContainer<?> application(Path root, String module, String version) {
        var image = new ImageFromDockerfile().withFileFromPath("app.jar", root.resolve(module + "/target/" + module + "-" + version + ".jar"))
            .withDockerfileFromBuilder(builder -> builder.from("eclipse-temurin:21-jre-jammy")
                .workDir("/app").copy("app.jar", "/app/app.jar").entryPoint("java", "-jar", "/app/app.jar").build());
        // Use HTTP for this setup test. Normal profiles use mTLS, and the identity check still blocks data APIs.
        return new ApplicationContainer(image).withEnv("SPRING_PROFILES_ACTIVE", "local")
            .withEnv("SERVER_ADDRESS", "0.0.0.0").withEnv("SERVER_PORT", "8443")
            .withEnv("STORE_DATA_DIRECTORY", "/tmp/shards").withExposedPorts(8443)
            .waitingFor(Wait.forHttp("/api/v1/capabilities").forPort(8443).withStartupTimeout(Duration.ofSeconds(90)));
    }

    static class ApplicationContainer extends GenericContainer<ApplicationContainer> {
        ApplicationContainer(Future<String> image) { super(image); }

        @Override public InspectContainerResponse getContainerInfo() {
            // Docker can assign a different host port after stop/start.
            return getCurrentContainerInfo();
        }
    }
}
