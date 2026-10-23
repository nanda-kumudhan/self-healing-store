package dev.healstore.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.healstore.client.core.ClientFileOperations;
import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.*;

/** Real encrypted data tests. Run with -Pintegration and an accessible Docker daemon. */
class EncryptedClusterIT {
    @TempDir Path work;

    @Test
    void encryptedFilesSurviveKilledContainersCorruptionAndProxyPartitions() throws Exception {
        Path root = Path.of(System.getProperty("project.root"));
        Path certs = work.resolve("certs");
        var certificates =
            new ProcessBuilder("bash", root.resolve("infra/certs/dev-certs.sh").toString(), certs.toString())
                .redirectErrorStream(true)
                .redirectOutput(work.resolve("certificates.log").toFile())
                .start();
        assertEquals(0, certificates.waitFor(), "Certificate generation failed");
        String password = Files.readString(certs.resolve("store-password"));
        String version = System.getProperty("store.version");
        List<GenericContainer<?>> nodes = new ArrayList<>();
        try (var network = Network.newNetwork(); var db = new PostgreSQLContainer<>("postgres:17.6-alpine")
                                                     .withNetwork(network)
                                                     .withNetworkAliases("postgres");
            var coordinator = app(root, "coordinator", version, "coordinator", certs, password)
                .withNetwork(network)
                .withNetworkAliases("coordinator")
                .withEnv("STORE_REPAIR_POLL_MS", "3600000");
            var proxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                .withNetwork(network)) {
            db.start();
            coordinator.withEnv("DATABASE_URL", "jdbc:postgresql://postgres:5432/" + db.getDatabaseName())
                .withEnv("DATABASE_USER", db.getUsername())
                .withEnv("DATABASE_PASSWORD", db.getPassword())
                .start();
            URI origin =
                URI.create("https://" + coordinator.getHost() + ":" + coordinator.getMappedPort(8443));
            for (int i = 1; i <= 7; i++) {
                String name = "node-" + i;
                var node = app(root, "storage-node", version, name, certs, password)
                               .withNetwork(network)
                               .withNetworkAliases(name)
                               .withEnv("STORE_COORDINATOR", "https://coordinator:8443")
                               .withEnv("STORE_NODE_NAME", name)
                               .withEnv("STORE_ENDPOINT", "https://" + name + ":8443")
                               .withEnv("STORE_SCRUB_MS", "3600000");
                nodes.add(node);
                node.start();
            }
            proxy.start();
            Map<String, ToxiproxyContainer.ContainerProxy> routes = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++)
                routes.put("node-" + (i + 1), proxy.getProxy(nodes.get(i), 8443));
            var transport = new HttpTransport(certs.resolve("client/client.p12"),
                certs.resolve("client/truststore.p12"), password.toCharArray(), false);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (transport.json(origin, "GET", "/api/v1/nodes", null, List.class).size() < 7) {
                if (System.nanoTime() > deadline)
                    fail("Nodes did not register");
                Thread.sleep(200);
            }
            var files = new ClientFileOperations(transport, origin, endpoint -> {
                var route = routes.get(endpoint.getHost());
                return URI.create("https://" + proxy.getHost() + ":" + route.getProxyPort());
            });
            byte[] input = new byte[1024 * 1024 + 19];
            new Random(321).nextBytes(input);
            Path source = work.resolve("input");
            Files.write(source, input);
            char[] pass = "integration test passphrase".toCharArray();
            UUID id = files.upload(source, pass);
            StoredChunk chunk =
                transport.json(origin, "GET", "/api/v1/objects/" + id + "/chunks/0", null, StoredChunk.class);
            var holders =
                chunk.locations()
                    .stream()
                    .map(location
                        -> nodes.get(Integer.parseInt(location.endpoint().getHost().substring(5)) - 1))
                    .toList();
            for (int i = 0; i < 3; i++) {
                var holder = holders.get(i);
                holder.getDockerClient().stopContainerCmd(holder.getContainerId()).withTimeout(1).exec();
                Path out = work.resolve("lost-" + (i + 1));
                if (i < 2) {
                    files.download(id, out, pass);
                    assertArrayEquals(input, Files.readAllBytes(out));
                } else {
                    assertThrows(IllegalStateException.class, () -> files.download(id, out, pass));
                    assertFalse(Files.exists(out));
                }
            }
            var third = holders.get(2);
            third.getDockerClient().startContainerCmd(third.getContainerId()).exec();
            awaitHealthy(third, transport);
            RepairResult result = transport.json(
                origin, "POST", "/api/v1/objects/" + id + "/repairs", null, RepairResult.class);
            assertEquals(0, result.pendingShards());
            assertTrue(result.repairedShards() >= 2);
            Path repaired = work.resolve("repaired");
            files.download(id, repaired, pass);
            assertArrayEquals(input, Files.readAllBytes(repaired));
            // Partition a real TLS data route. Both client GET and PUT use these proxy routes.
            var current =
                transport.json(origin, "GET", "/api/v1/objects/" + id + "/chunks/0", null, StoredChunk.class);
            var route = routes.get(current.locations().getFirst().endpoint().getHost());
            route.setConnectionCut(true);
            Path partitioned = work.resolve("partitioned");
            files.download(id, partitioned, pass);
            assertArrayEquals(input, Files.readAllBytes(partitioned));
            route.setConnectionCut(false);
            // Flip one stored byte with dd, then prove that download and scrub detect it.
            int badIndex =
                Integer.parseInt(current.locations().getFirst().endpoint().getHost().substring(5)) - 1;
            var badNode = nodes.get(badIndex);
            UUID shard = current.chunk().shards().getFirst().id();
            byte[] good =
                transport.send(URI.create("https://" + badNode.getHost() + ":" + badNode.getMappedPort(8443)),
                    "GET", "/api/v1/shards/" + shard, null, "application/octet-stream");
            Path byteFile = work.resolve("byte");
            Files.write(byteFile, new byte[] {(byte) (good[0] ^ 1)});
            badNode.copyFileToContainer(MountableFile.forHostPath(byteFile), "/tmp/corrupt-byte");
            var corruption = badNode.execInContainer("dd", "if=/tmp/corrupt-byte",
                "of=/tmp/shards/" + shard + ".shard", "bs=1", "count=1", "conv=notrunc");
            assertEquals(0, corruption.getExitCode());
            Path corrupted = work.resolve("corrupted");
            files.download(id, corrupted, pass);
            assertArrayEquals(input, Files.readAllBytes(corrupted));
            URI badOrigin = URI.create("https://" + badNode.getHost() + ":" + badNode.getMappedPort(8443));
            var scrub = transport.json(badOrigin, "POST", "/api/v1/scrubs", null, Map.class);
            assertTrue(((Number) scrub.get("damagedShards")).intValue() > 0);
            // Restore capacity for the next repair. Seven nodes had two stopped holders.
            for (int i = 0; i < 2; i++) {
                var node = holders.get(i);
                node.getDockerClient().startContainerCmd(node.getContainerId()).exec();
                awaitHealthy(node, transport);
            }
            Thread.sleep(2200);
            result = transport.json(
                origin, "POST", "/api/v1/objects/" + id + "/repairs", null, RepairResult.class);
            assertEquals(0, result.pendingShards());
            Path finalCopy = work.resolve("final");
            files.download(id, finalCopy, pass);
            assertArrayEquals(input, Files.readAllBytes(finalCopy));
        } finally {
            for (var node : nodes) node.close();
        }
    }

    private static void awaitHealthy(GenericContainer<?> node, HttpTransport http) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                http.json(URI.create("https://" + node.getHost() + ":" + node.getMappedPort(8443)), "GET",
                    "/api/v1/capabilities", null, Object.class);
                return;
            } catch (Exception e) {
                if (System.nanoTime() > deadline)
                    throw e;
                Thread.sleep(200);
            }
        }
    }

    private static GenericContainer<?> app(
        Path root, String module, String version, String identity, Path certs, String password) {
        var image =
            new ImageFromDockerfile()
                .withFileFromPath(
                    "app.jar", root.resolve(module + "/target/" + module + "-" + version + ".jar"))
                .withDockerfileFromBuilder(b
                    -> b.from("eclipse-temurin:21-jre-jammy")
                        .copy("app.jar", "/app.jar")
                        .entryPoint("java", "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar", "/app.jar")
                        .build());
        return new InfrastructureIT.ApplicationContainer(image)
            .withCopyFileToContainer(MountableFile.forHostPath(certs.resolve(identity)), "/certs")
            .withEnv("TLS_KEY_STORE", "file:/certs/" + identity + ".p12")
            .withEnv("TLS_TRUST_STORE", "file:/certs/truststore.p12")
            .withEnv("TLS_STORE_PASSWORD", password)
            .withEnv("STORE_DATA_DIRECTORY", "/tmp/shards")
            .withExposedPorts(8443)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));
    }
}
