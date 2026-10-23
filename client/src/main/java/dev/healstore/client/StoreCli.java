package dev.healstore.client;

import dev.healstore.client.core.ClientFileOperations;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "store", mixinStandardHelpOptions = true, version = "store 0.1.0",
    description = "A storage project for learning about failures and encryption. Client-side encryption with "
                  + "3+2 erasure coding.",
    subcommands = {StoreCli.Status.class, StoreCli.Nodes.class, StoreCli.Upload.class,
        StoreCli.Download.class, StoreCli.Repair.class, StoreCli.Local.class})
public class StoreCli implements Runnable {
    @Option(names = "--coordinator", defaultValue = "https://localhost:8443", scope = ScopeType.INHERIT)
    URI coordinator;
    @Option(names = "--key-store", defaultValue = "infra/certs/generated/client/client.p12",
        scope = ScopeType.INHERIT)
    Path keyStore;
    @Option(names = "--trust-store", defaultValue = "infra/certs/generated/client/truststore.p12",
        scope = ScopeType.INHERIT)
    Path trustStore;
    @Option(names = "--allow-local-http", description = "Allow HTTP to localhost for local development.",
        scope = ScopeType.INHERIT)
    boolean allowLocalHttp;
    @Spec CommandSpec spec;
    @Option(names = "--node-host", scope = ScopeType.INHERIT,
        description = "Map Compose node endpoints to this host (e.g. localhost).")
    String nodeHost;
    @Option(names = "--node-base-port", defaultValue = "8443", scope = ScopeType.INHERIT) int nodeBasePort;
    ClientFileOperations files() throws Exception {
        char[] pass = System.getenv().getOrDefault("STORE_TLS_PASSWORD", "").toCharArray();
        try {
            var transport = new dev.healstore.common.io.HttpTransport(
                "https".equals(coordinator.getScheme()) ? keyStore : null, trustStore, pass,
                allowLocalHttp);
            return new ClientFileOperations(transport, coordinator, uri -> {
                if (nodeHost == null || !uri.getHost().matches("node-[1-9][0-9]*"))
                    return uri;
                int n = Integer.parseInt(uri.getHost().substring(5));
                return URI.create("https://" + nodeHost + ":" + (nodeBasePort + n));
            });
        } finally {
            java.util.Arrays.fill(pass, '\0');
        }
    }
    static char[] passphrase() {
        String env = System.getenv("STORE_PASSPHRASE");
        if (env != null)
            return env.toCharArray();
        if (System.console() != null)
            return System.console().readPassword("File passphrase: ");
        throw new IllegalArgumentException("Set STORE_PASSPHRASE or use an interactive terminal");
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    String request(String method, String path) throws Exception {
        return CoordinatorTransport.request(coordinator, keyStore, trustStore, allowLocalHttp, method, path);
    }

    public static CommandLine commandLine() {
        return new CommandLine(new StoreCli()).setExecutionExceptionHandler((error, command, parse) -> {
            command.getErr().println(error.getMessage());
            return 1;
        });
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
        description = "Check the coordinator and see its capabilities.")
    static class Status implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Override
        public Integer call() throws Exception {
            parent.spec.commandLine().getOut().println(parent.request("GET", "/api/v1/capabilities"));
            return 0;
        }
    }

    @Command(name = "nodes", mixinStandardHelpOptions = true, description = "List registered nodes ")
    static class Nodes implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Override
        public Integer call() throws Exception {
            parent.spec.commandLine().getOut().println(parent.request("GET", "/api/v1/nodes"));
            return 0;
        }
    }

    @Command(name = "upload", mixinStandardHelpOptions = true, description = "Upload an encrypted file ")
    static class Upload implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") Path source;
        @Override
        public Integer call() throws Exception {
            char[] pass = passphrase();
            try {
                parent.spec.commandLine().getOut().println(parent.files().upload(source, pass));
                return 0;
            } finally {
                java.util.Arrays.fill(pass, '\0');
            }
        }
    }

    @Command(name = "download", mixinStandardHelpOptions = true, description = "Download and check a file ")
    static class Download implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") UUID objectId;
        @Parameters(index = "1") Path destination;
        @Override
        public Integer call() throws Exception {
            char[] pass = passphrase();
            try {
                parent.files().download(objectId, destination, pass);
                return 0;
            } finally {
                java.util.Arrays.fill(pass, '\0');
            }
        }
    }

    @Command(name = "repair", mixinStandardHelpOptions = true, description = "Request a repair ")
    static class Repair implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") UUID objectId;
        @Override
        public Integer call() throws Exception {
            String response = parent.request("POST", "/api/v1/objects/" + objectId + "/repairs");
            parent.spec.commandLine().getOut().println(response);
            return dev.healstore.common.io.Json
                        .read(response, dev.healstore.common.model.Manifest.RepairResult.class)
                        .pendingShards()
                    == 0
                ? 0
                : 2;
        }
    }

    @Command(name = "local", mixinStandardHelpOptions = true,
        description = "Try a local encrypt, shard and reconstruct round trip ")
    static class Local implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") Path source;
        @Parameters(index = "1") Path destination;
        @Override
        public Integer call() throws Exception {
            new ClientFileOperations().localRoundTrip(source, destination);
            return 0;
        }
    }
}
