package dev.healstore.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import com.backblaze.erasure.ReedSolomon;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** Checks the Backblaze library. The encrypted file pipeline still needs its own tests. */
class ReedSolomonProperties {
    @Property(tries = 100)
    void upstreamRestoresAnyTwoMissingShards(@ForAll @IntRange(min = 0, max = 65536) int bytes,
                                            @ForAll long seed) {
        var random = new Random(seed);
        int length = Math.max(1, (bytes + 2) / 3);
        byte[][] shards = new byte[5][length];
        for (int i = 0; i < bytes; i++) shards[i / length][i % length] = (byte) random.nextInt(256);
        var codec = ReedSolomon.create(3, 2);
        codec.encodeParity(shards, 0, length);
        byte[][] original = new byte[5][];
        for (int i = 0; i < 5; i++) original[i] = shards[i].clone();
        boolean[] present = {true, true, true, true, true};
        int first = random.nextInt(5);
        int second = (first + 1 + random.nextInt(4)) % 5;
        shards[first] = new byte[length]; shards[second] = new byte[length];
        present[first] = false; present[second] = false;
        codec.decodeMissing(shards, present, 0, length);
        for (int i = 0; i < 5; i++) assertArrayEquals(original[i], shards[i]);
    }
}
