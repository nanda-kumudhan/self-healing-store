package dev.healstore.node.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
public class ShardTooLargeException extends RuntimeException {
    public ShardTooLargeException() { super("Shard exceeds the configured size limit."); }
}
