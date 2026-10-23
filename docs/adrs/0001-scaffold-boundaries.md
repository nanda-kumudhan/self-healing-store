# ADR 0001: backend setup before the storage logic

Status: historical initial setup. Superseded by [ADR 0002](0002-store-protocol.md) after the user authorized the core implementation. The statements below describe the original scaffold, not the current store.

## Why

I'm doing this project to learn placement, failure detection, repair and the security decisions around them. I've used generated scaffolding for the Spring Boot apps, endpoints and supporting scripts, and left the main algorithms for myself. Kafka is out of scope and the dashboard is on hold.

## Choices so far

Java 21, Maven, Spring Boot REST and picocli give me the basic apps and CLI. PostgreSQL stores node registrations through JPA and Flyway. H2 makes local development easier without starting a database container. The earlier README mentioned gRPC, but I'm using REST here.

Spring handles mTLS with a separate development certificate for each service. The OpenSSL script creates those certificates. Application permissions are still unfinished: trusting a certificate doesn't mean that caller should be allowed to write any shard or claim any node ID.

The client pipeline, manifest, key/nonce handling, placement, failure detector, repair, shard checks and scrubber are placeholders. They fail with `501 HANDS_ON_REQUIRED` instead of pretending an operation worked. The status endpoint reports that file operations aren't ready.

Backblaze's Reed-Solomon source is copied unchanged at a fixed commit, with its MIT licence. Bouncy Castle is available for Argon2id. I don't want to write my own crypto primitives or field arithmetic.

The registry and disk helper cover some basic database/file I/O. They don't decide shard ownership, node health or the manifest format. The plots need actual CSV measurements; the probability model is still to do.

## What this means for now

I can start the services and check the TLS and database setup before the full store works. The setup tests don't prove that files survive node failures. That needs the core implementation and separate tests.

The coordinator and database are single points of failure. If I take this further, I'll need to look at metadata recovery/replication, failure domains, repair coordination and whatever bottlenecks the measurements show. There's no replicated coordinator or consensus protocol here.

Six nodes leave one spare for five shard holders. After two permanent holder failures, I'll need returning/replacement nodes or a seventh node to restore all five holders. Being able to read the data and having full redundancy again are separate checks.
