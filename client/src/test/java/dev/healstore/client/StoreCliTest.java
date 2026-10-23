package dev.healstore.client;

import static org.junit.jupiter.api.Assertions.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreCliTest {
    @TempDir Path directory;

    @Test void unfinishedUploadDoesNotReadEvenANonexistentSource() {
        var command = StoreCli.commandLine();
        var error = new StringWriter(); command.setErr(new PrintWriter(error));
        assertEquals(3, command.execute("upload", directory.resolve("absent").toString()));
        assertTrue(error.toString().contains("CLIENT_PIPELINE"));
    }

    @Test void unfinishedDownloadDoesNotTruncateExistingOutput() throws Exception {
        Path output = directory.resolve("output"); Files.writeString(output, "keep me");
        var command = StoreCli.commandLine(); command.setErr(new PrintWriter(new StringWriter()));
        assertEquals(3, command.execute("download", UUID.randomUUID().toString(), output.toString()));
        assertEquals("keep me", Files.readString(output));
    }

    @Test void unfinishedLocalCommandDoesNotCreateAnOutput() {
        var command = StoreCli.commandLine(); command.setErr(new PrintWriter(new StringWriter()));
        Path output = directory.resolve("output");
        assertEquals(3, command.execute("local", directory.resolve("input").toString(), output.toString()));
        assertFalse(Files.exists(output));
    }

    @Test void refusesRemotePlaintextTransportBeforeConnecting() {
        var command = StoreCli.commandLine();
        var error = new StringWriter(); command.setErr(new PrintWriter(error));
        assertEquals(1, command.execute("status", "--coordinator", "http://example.com:8080", "--allow-local-http"));
        assertTrue(error.toString().contains("HTTPS is required"));
    }
}
