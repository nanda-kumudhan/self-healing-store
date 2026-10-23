package dev.healstore.common.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** A registered node might be offline; this doesn't say which shards it holds. */
public record NodeView(UUID id, URI endpoint, Instant registeredAt) { }
