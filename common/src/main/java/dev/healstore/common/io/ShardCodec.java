package dev.healstore.common.io;

import com.backblaze.erasure.ReedSolomon;
import java.security.MessageDigest;
import java.util.*;

/** All field arithmetic comes from the vendored Backblaze library. */
public final class ShardCodec {
    private ShardCodec() {}
    public static byte[][] encode(byte[] ciphertext) {
        int size = (ciphertext.length + 2) / 3;
        byte[][] shards = new byte[5][size];
        for (int i = 0; i < ciphertext.length; i++) shards[i / size][i % size] = ciphertext[i];
        ReedSolomon.create(3, 2).encodeParity(shards, 0, size);
        return shards;
    }
    public static void recover(byte[][] shards, boolean[] present, int size) {
        if (shards.length != 5 || present.length != 5)
            throw new IllegalArgumentException("Expected five shards");
        int count = 0;
        for (int i = 0; i < 5; i++) {
            if (present[i]) {
                if (shards[i] == null || shards[i].length != size)
                    throw new IllegalArgumentException("Wrong shard length");
                count++;
            } else
                shards[i] = new byte[size];
        }
        if (count < 3)
            throw new IllegalStateException("Fewer than three valid shards; cannot recover chunk");
        ReedSolomon.create(3, 2).decodeMissing(shards, present, 0, size);
    }
    public static byte[] join(byte[][] shards, int length) {
        byte[] result = new byte[length];
        int size = shards[0].length;
        for (int i = 0; i < length; i++) result[i] = shards[i / size][i % size];
        return result;
    }
    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
