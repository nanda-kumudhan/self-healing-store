package dev.healstore.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.healstore.client.core.*;
import dev.healstore.common.io.ShardCodec;
import dev.healstore.common.model.Manifest;
import java.util.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

class EncryptedPipelineProperties {
    @Property(tries = 35)
    void authenticatedChunksSurviveEveryPairOfMissingShards(
        @ForAll @IntRange(min = 1, max = 65536) int size, @ForAll long seed) throws Exception {
        byte[] plain = new byte[size];
        new Random(seed).nextBytes(plain);
        byte[] key = FileCrypto.random(32);
        UUID id = UUID.randomUUID();
        try (var crypto = new FileCrypto(id, key)) {
            byte[][] original = ShardCodec.encode(crypto.encrypt(0, plain));
            var chunk = ClientFileOperations.metadata(id, 0, size, original, crypto);
            crypto.verify(chunk.canonical(), chunk.authentication());
            for (int a = 0; a < 5; a++)
                for (int b = a + 1; b < 5; b++) {
                    byte[][] shards = new byte[5][];
                    boolean[] valid = new boolean[5];
                    for (int i = 0; i < 5; i++) {
                        valid[i] = i != a && i != b;
                        shards[i] = valid[i] ? original[i].clone() : null;
                    }
                    ShardCodec.recover(shards, valid, chunk.shardBytes());
                    assertArrayEquals(plain, crypto.decrypt(0, size, ShardCodec.join(shards, size + 16)));
                }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
    @Example
    void tagsOrderAndManifestTamperingAreRejected() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] key = FileCrypto.random(32);
        try (var c = new FileCrypto(id, key)) {
            byte[] encrypted = c.encrypt(0, new byte[] {1, 2, 3});
            assertThrows(Exception.class, () -> c.decrypt(1, 3, encrypted));
            encrypted[0] ^= 1;
            assertThrows(Exception.class, () -> c.decrypt(0, 3, encrypted));
            String tag = c.authenticate(Manifest.utf8("original"));
            assertThrows(SecurityException.class, () -> c.verify(Manifest.utf8("changed"), tag));
        }
    }
    @Example
    void independentUploadsDoNotReuseKeyAndNoncePairs() throws Exception {
        byte[] plain = {1, 2, 3};
        try (var a = new FileCrypto(UUID.randomUUID(), FileCrypto.random(32));
            var b = new FileCrypto(UUID.randomUUID(), FileCrypto.random(32))) {
            assertFalse(Arrays.equals(a.encrypt(0, plain), b.encrypt(0, plain)));
            assertFalse(Arrays.equals(a.encrypt(0, plain), a.encrypt(1, plain)));
        }
    }
    @Example
    void keyEnvelopeRejectsWrongPasswordAndHeaderChanges() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] key = FileCrypto.random(32);
        char[] pass = "correct test passphrase".toCharArray();
        try (var c = new FileCrypto(id, key)) {
            var h = c.finish(FileCrypto.wrap(id, key, pass), 0, 0);
            try (var opened = FileCrypto.unwrap(h, pass)) {
                assertNotNull(opened);
            }
            assertThrows(
                Exception.class, () -> FileCrypto.unwrap(h, "different test passphrase".toCharArray()));
            var changed =
                new Manifest.Header(1, id, h.salt(), h.wrapNonce(), h.wrappedKey(), 1, 1, h.authentication());
            assertThrows(SecurityException.class, () -> FileCrypto.unwrap(changed, pass));
        }
    }
    @Example
    void threeMissingShardsFailClearly() {
        assertThrows(IllegalStateException.class, () -> ShardCodec.recover(new byte[5][], new boolean[5], 1));
    }
}
