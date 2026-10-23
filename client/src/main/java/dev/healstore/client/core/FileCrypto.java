package dev.healstore.client.core;

import dev.healstore.common.model.Manifest;
import dev.healstore.common.model.Manifest.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Fixed KDF parameters prevent a manifest from asking the client to allocate arbitrary memory. */
public final class FileCrypto implements AutoCloseable {
    private final byte[] dataKey;
    private final byte[] macKey;
    private final UUID id;
    public FileCrypto(UUID id, byte[] key) throws Exception {
        this.id = id;
        dataKey = derive(key, "data-v1");
        macKey = derive(key, "manifest-v1");
    }
    public static byte[] random(int count) {
        byte[] b = new byte[count];
        new SecureRandom().nextBytes(b);
        return b;
    }
    public static Header wrap(UUID id, byte[] key, char[] password) throws Exception {
        byte[] salt = random(16), nonce = random(12), kek = passwordKey(password, salt);
        try {
            byte[] wrapped =
                crypt(Cipher.ENCRYPT_MODE, kek, nonce, Manifest.utf8("healstore-key-v1|" + id), key);
            return new Header(1, id, b64(salt), b64(nonce), b64(wrapped), 0, 0, "");
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }
    public static FileCrypto unwrap(Header h, char[] password) throws Exception {
        h.validate();
        byte[] kek = passwordKey(password, Manifest.decoded(h.salt(), 16));
        byte[] key = null;
        try {
            key = crypt(Cipher.DECRYPT_MODE, kek, Manifest.decoded(h.wrapNonce(), 12),
                Manifest.utf8("healstore-key-v1|" + h.objectId()), Manifest.decoded(h.wrappedKey(), 48));
            var crypto = new FileCrypto(h.objectId(), key);
            try {
                crypto.verify(h.canonical(), h.authentication());
                return crypto;
            } catch (Exception e) {
                crypto.close();
                throw e;
            }
        } finally {
            Arrays.fill(kek, (byte) 0);
            if (key != null)
                Arrays.fill(key, (byte) 0);
        }
    }
    private static byte[] passwordKey(char[] pass, byte[] salt) {
        if (pass.length < 12)
            throw new IllegalArgumentException("Use a file passphrase of at least 12 characters");
        var generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(65536)
                .withIterations(3)
                .withParallelism(1)
                .build());
        byte[] result = new byte[32];
        generator.generateBytes(pass, result);
        return result;
    }
    private static byte[] derive(byte[] key, String label) throws Exception {
        var m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(Manifest.utf8(label));
    }
    public String authenticate(byte[] value) throws Exception {
        return b64(derive(macKey, new String(value, java.nio.charset.StandardCharsets.UTF_8)));
    }
    public void verify(byte[] value, String tag) throws Exception {
        if (!MessageDigest.isEqual(
                Manifest.decoded(tag, 32), Base64.getDecoder().decode(authenticate(value))))
            throw new SecurityException("Manifest authentication failed");
    }
    public Header finish(Header h, long bytes, int chunks) throws Exception {
        var unsigned = new Header(1, id, h.salt(), h.wrapNonce(), h.wrappedKey(), bytes, chunks, "");
        return new Header(1, id, h.salt(), h.wrapNonce(), h.wrappedKey(), bytes, chunks,
            authenticate(unsigned.canonical()));
    }
    public byte[] encrypt(int index, byte[] plaintext) throws Exception {
        return chunk(Cipher.ENCRYPT_MODE, index, plaintext.length, plaintext);
    }
    public byte[] decrypt(int index, int plainBytes, byte[] ciphertext) throws Exception {
        return chunk(Cipher.DECRYPT_MODE, index, plainBytes, ciphertext);
    }
    private byte[] chunk(int mode, int index, int size, byte[] value) throws Exception {
        if (index < 0)
            throw new IllegalArgumentException("Chunk index overflow");
        byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(index).array();
        return crypt(
            mode, dataKey, nonce, Manifest.utf8("healstore-data-v1|" + id + "|" + index + "|" + size), value);
    }
    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] value)
        throws Exception {
        var c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad);
        return c.doFinal(value);
    }
    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
    @Override
    public void close() {
        Arrays.fill(dataKey, (byte) 0);
        Arrays.fill(macKey, (byte) 0);
    }
}
