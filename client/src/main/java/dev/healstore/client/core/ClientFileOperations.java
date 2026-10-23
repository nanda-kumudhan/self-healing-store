package dev.healstore.client.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import java.nio.file.Path;
import java.util.UUID;

/** These placeholders fail before reading input, creating output or sending any data. */
public class ClientFileOperations {
    public void upload(Path source) {
        // HANDS_ON: read chunks, handle keys/nonces/AAD, encrypt, encode and commit the upload.
        throw new HandsOnRequiredException(HandsOnTask.CLIENT_PIPELINE);
    }

    public void download(UUID objectId, Path destination) {
        // HANDS_ON: check the manifest/shards, reconstruct, decrypt and publish the finished file.
        throw new HandsOnRequiredException(HandsOnTask.CLIENT_PIPELINE);
    }

    public void localRoundTrip(Path source, Path destination) {
        // HANDS_ON: get a local file round trip working here before adding the network.
        throw new HandsOnRequiredException(HandsOnTask.CLIENT_PIPELINE);
    }
}
