package dev.healstore.common.api;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;

public record RegisterNodeRequest(@NotNull UUID id, @NotNull URI endpoint) { }
