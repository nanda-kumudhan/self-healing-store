# ADR 0002: encrypted chunks, committed metadata and repair

Status: implemented. This supersedes the placeholder boundary in ADR 0001 after the user authorized the core work.

## File and key format

I use fixed version 1 parameters so the input cannot request arbitrary KDF memory or a different code layout. Chunks are at most 1 MiB; all except the last are full. A fresh 32-byte root key and UUID are generated for every upload. Argon2id version 1.3 uses 64 MiB, three passes and one lane with a random 16-byte salt. It produces an AES-256 wrapping key. A random 12-byte wrapping nonce and object-specific AAD protect the root key with AES-GCM.

HMAC-SHA-256 of separate fixed labels derives the data and manifest keys from the random root key. This is a fixed-purpose PRF derivation, not a new encryption primitive or a claim to implement HKDF. The data nonce is a 32-bit zero prefix and 64-bit chunk index. The data key is unique to an upload, indices never repeat in that upload, and retries after interruption start a fresh object/key. This version does not resume or edit an object. GCM AAD binds the format label, object UUID, index and plaintext length. The ciphertext includes its authentication tag before coding.

The header contains the version, UUID, key envelope, total bytes and chunk count. Each chunk contains its UUID/index/length, five unique shard IDs and their SHA-256 hashes. Header and chunk MACs use fixed, domain-separated UTF-8 field serialization with explicit delimiters; fields have constrained alphabets. There are no arbitrary file names in that encoding. Locations are outside these MACs so keyless repair can move bytes. The client verifies every MAC and enforces chunk lengths/order before accepting reconstructed plaintext. The final byte count prevents truncation.

Empty files have zero chunks and an authenticated final header. Version 1 is bounded by the signed Java chunk counter. Keys and passphrase buffers are wiped best effort; Java, crypto providers and the environment may retain copies.

## Upload and download

The coordinator persists an initial uncommitted header owned by the client's certificate fingerprint. A transaction locks the object while appending an immutable chunk descriptor and five distinct assignments. Exact plan retries return the existing plan. Conflicting descriptors, gaps, short nonfinal chunks and writes after commit fail.

Before each node PUT/GET, the node asks the coordinator for the expected size/hash and permission for its node ID and caller. Bytes must match the reservation. PUT publishes a synced temporary file with a hard link, refusing overwrites. An atomic sidecar stores the trusted integrity cache; startup removes unfinished temporary files. GET verifies against the coordinator again. The client also verifies against its authenticated descriptor, including after any race between a node's hash check and its streamed response.

The client uploads all five shards, then asks the coordinator to read back and verify that chunk. Only verified chunks count towards the final object commit. The final transaction checks header envelope, count and lengths. It cannot verify the client's MAC without the key; that check belongs to the client. A failed upload stays uncommitted. Garbage collection of abandoned reservations/shards is future work, and lost commit responses currently need inspection rather than a full resumable protocol.

A download opens a temporary file in the destination directory, accepts only valid hashes, reconstructs from at least three shards, verifies GCM, and checks the final length. It publishes with a no-overwrite hard link after syncing the completed file. Any error deletes the temporary output and leaves an existing destination alone. Unverified partial plaintext is never published under the destination name. A complete power-cut/filesystem campaign remains untested.

## Placement, health and repair

I rank candidate nodes by SHA-256 of chunk identity and node UUID. Ranking is stable under input order and preserves existing relative ranks when a node joins. All five holders are distinct. Registry membership is not liveness. Nodes send authenticated heartbeats every two seconds. New placement needs a heartbeat after coordinator startup and within ten seconds. Dead-node repair waits ten plus twenty seconds, including a startup grace. This uses database wall-clock timestamps and assumes reasonably stable clocks; a monotonic distributed lease protocol is outside this version.

Automatic repair marks assignments missing after the delay and scans committed chunks with damage. Fewest surviving holders go first. One worker limits aggregate shard reads/writes to 10 MiB/s; HTTP/TLS headers and node-to-coordinator metadata calls are outside that cap. The cap is enforced at chunk/shard boundaries, so short bursts are possible. Unrecoverable or capacity-blocked chunks receive a persistent retry deadline to avoid starving other work.

The worker fetches available shards, checks reserved hashes, reconstructs ciphertext using Backblaze's codec, and checks each recovered hash. It grants a target through a persisted pending assignment, writes the original shard ID, reads it back, then conditionally switches the assignment from the expected old node to the target. A retry finding an existing target shard must verify its bytes before accepting it. The old holder remains authoritative until the switch. Pending target grants allow interrupted repairs to retry safely; unreferenced old replicas are not collected yet.

A synchronous manual repair checks every chunk and returns completed/pending shard counts and server elapsed time. The CLI returns a nonzero status if anything remains pending. Automatic discovery and assignments survive coordinator restart through SQL tables. This is one worker behind one coordinator, without distributed worker fencing or multiple active coordinators.

Scrubbing examines sidecars as well as shard files so deletion on an otherwise live node is visible. Damage reports can only affect that node's assigned shards. Scrubbing and heartbeats have separate scheduler capacity. Read verification and the client still check hashes independently of the cache.

## Trust and capacity

Spring requires trusted client certificates, with the current `server.ssl.client-auth: need` and PKCS12 settings described in the [Spring Boot documentation](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/). The private CA reserves exact CN roles. A trusted `node-1` cannot register/heartbeat another node, a client cannot act as a node, and a storage node cannot read another node's grant. The loopback registration exception exists only for the explicit process-test configuration. Outbound Java clients also require a coordinator or storage-node server role during the TLS handshake, in addition to chain and hostname checks, before sending HTTP data. Changing certificate owners or admitting arbitrary public peers needs a different identity lifecycle.

The coordinator is trusted for placement, authorization, expected hashes during repair and availability. It cannot decrypt data or forge client MACs, but can delete metadata, deny service or replay an old valid object. The design does not prove remote storage or hide file lengths, access patterns or node locations. Shard GETs currently consult the coordinator, making it part of read availability too.

Six nodes have only one spare beyond five holders. Two dead holders leave three surviving shards and four live nodes. Reads work; full redundancy cannot be restored until a node returns or another spare joins. The optional seventh node gives five live destinations after two permanent failures. I do not co-locate two shards of a chunk to disguise missing capacity.

At ten times the scale I would measure shared repair contention, strengthen queue fairness, add explicit failure domains and replica/backup planning for metadata, and introduce leader fencing before running multiple workers or coordinators.
