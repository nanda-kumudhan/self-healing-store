# Project scope

This is a student learning project. The backend setup and supporting tools can be built in one pass; the earlier request to confirm each step no longer applies. Keep the core design work for the user.

- Do not create a dashboard or status web page until the user explicitly asks.
- Do not add Kafka; the user explicitly chose the original backend without Kafka.
- Leave the key hands-on logic to the user: placement, failure detection, repair, scrubbing, manifest format, nonce/key discipline, peer identity authorization, encrypted chunk pipeline and shard integrity policy.
- Preserve the explicit `HandsOnRequiredException` boundaries until the user supplies an implementation or changes this instruction. Never replace them with permissive or fake success defaults.
- Framework wiring, REST/CLI adapters, persistence plumbing, opaque byte I/O, containers, external libraries, development CA scripts, fault injection and plotting are in scope.
- Never describe infrastructure smoke tests as proof of encrypted upload/download, corruption recovery or two-node failure tolerance. These await the core implementation.
- Do not fabricate measurements, durability probabilities, model results or a demo video.

Write docs and comments in plain language, like a CS student explaining their own project. Use first person in project notes where it fits. Keep technical details accurate and say clearly what is unfinished.

Use Java 21 to build. Run `bash scripts/check.sh`; Docker integration tests are opt-in with `./mvnw -Pintegration verify` and require an accessible daemon. Do not change host Docker permissions to make tests pass.
