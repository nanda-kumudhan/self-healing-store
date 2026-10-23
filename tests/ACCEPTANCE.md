# Things the finished store needs to pass

This is my test checklist for the core implementation. These checks aren't all implemented or passing yet.

- Round-trip empty files, one-byte files, sizes just below/at/above a chunk boundary, several chunks and a large streamed file. Keep the seed when a jqwik run fails.
- Try every combination of zero, one and two missing shards out of five. The recovered bytes must match the input. The current property test only checks the Backblaze library.
- With fewer than three valid shards, return a clear error and leave no published partial output.
- Flip a shard byte. A trusted expected hash should reject it before reconstruction. Missing and corrupted shards count against the same recovery limit.
- Change a ciphertext/tag, mix up chunks or tamper with the manifest. Authentication should fail without returning unverified plaintext.
- Check nonce uniqueness within the chosen key scope, including retries, interrupted uploads, concurrent clients and rewritten objects.
- Reject clients with no certificate or an untrusted one during TLS (the smoke test covers this). Also reject a trusted certificate with the wrong role/node ID (still to implement).
- Upload with six nodes, find the five actual holders, then stop one and two holders. Downloads should work. Stop three and check that the download fails cleanly.
- Repair onto enough separate live nodes, verify hashes and recovered bytes, then download with a different allowed set of holders unavailable.
- Test both sides of the heartbeat-delay boundary with a controlled clock. A short interruption shouldn't start a repair storm.
- Send the real storage traffic through Toxiproxy before testing a partition. Make sure a direct connection isn't bypassing it.
- Check duplicate/retried repairs, coordinator restarts, concurrent metadata changes, full disks and unavailable repair targets.
- Measure completed repair time at several data sizes, throughput versus a plain copy and peak RSS as file size grows. Repeat runs and record failures and machine/container settings.
- Use measured repair times in the model. Compare 3+2, 4+2 and three replicas with the same assumptions, and explain correlated failures and limited spares.

Two permanent losses in a six-node cluster leave only four live nodes. Return or replace capacity before claiming that all five holders are restored. More detail is in `docs/HANDS_ON.md`.
