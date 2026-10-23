package dev.healstore.client;

import dev.healstore.client.core.ClientFileOperations;
import dev.healstore.common.core.HandsOnRequiredException;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "store", mixinStandardHelpOptions = true, version = "store 0.1.0-scaffold",
    description = "A storage project for learning about failures and encryption. File operations are unfinished.",
    subcommands = {StoreCli.Status.class, StoreCli.Nodes.class, StoreCli.Upload.class,
        StoreCli.Download.class, StoreCli.Repair.class, StoreCli.Local.class})
public class StoreCli implements Runnable {
    @Option(names = "--coordinator", defaultValue = "https://localhost:8443", scope = ScopeType.INHERIT)
    URI coordinator;
    @Option(names = "--key-store", defaultValue = "infra/certs/generated/client/client.p12", scope = ScopeType.INHERIT)
    Path keyStore;
    @Option(names = "--trust-store", defaultValue = "infra/certs/generated/client/truststore.p12", scope = ScopeType.INHERIT)
    Path trustStore;
    @Option(names = "--allow-local-http", description = "Allow HTTP to localhost for local development.", scope = ScopeType.INHERIT)
    boolean allowLocalHttp;
    @Spec CommandSpec spec;
    final ClientFileOperations files = new ClientFileOperations();

    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }

    String request(String method, String path) throws Exception {
        return CoordinatorTransport.request(coordinator, keyStore, trustStore, allowLocalHttp, method, path);
    }

    public static CommandLine commandLine() {
        return new CommandLine(new StoreCli()).setExecutionExceptionHandler((error, command, parse) -> {
            command.getErr().println(error.getMessage());
            return error instanceof HandsOnRequiredException ? 3 : 1;
        });
    }

    public static void main(String[] args) { System.exit(commandLine().execute(args)); }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Check the coordinator and see what is unfinished.")
    static class Status implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Override public Integer call() throws Exception {
            parent.spec.commandLine().getOut().println(parent.request("GET", "/api/v1/capabilities")); return 0;
        }
    }

    @Command(name = "nodes", mixinStandardHelpOptions = true, description = "List registered nodes (identity checks still need implementing).")
    static class Nodes implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Override public Integer call() throws Exception {
            parent.spec.commandLine().getOut().println(parent.request("GET", "/api/v1/nodes")); return 0;
        }
    }

    @Command(name = "upload", mixinStandardHelpOptions = true, description = "Upload an encrypted file (not implemented yet).")
    static class Upload implements Runnable {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") Path source;
        @Override public void run() { parent.files.upload(source); }
    }

    @Command(name = "download", mixinStandardHelpOptions = true, description = "Download and check a file (not implemented yet).")
    static class Download implements Runnable {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") UUID objectId;
        @Parameters(index = "1") Path destination;
        @Override public void run() { parent.files.download(objectId, destination); }
    }

    @Command(name = "repair", mixinStandardHelpOptions = true, description = "Request a repair (identity checks and repair logic are unfinished).")
    static class Repair implements Callable<Integer> {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") UUID objectId;
        @Override public Integer call() throws Exception {
            parent.spec.commandLine().getOut().println(parent.request("POST", "/api/v1/objects/" + objectId + "/repairs")); return 0;
        }
    }

    @Command(name = "local", mixinStandardHelpOptions = true, description = "Try a local encrypt, shard and reconstruct round trip (not implemented yet).")
    static class Local implements Runnable {
        @ParentCommand StoreCli parent;
        @Parameters(index = "0") Path source;
        @Parameters(index = "1") Path destination;
        @Override public void run() { parent.files.localRoundTrip(source, destination); }
    }
}
