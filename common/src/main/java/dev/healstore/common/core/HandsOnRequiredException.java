package dev.healstore.common.core;

public final class HandsOnRequiredException extends RuntimeException {
    private final HandsOnTask task;

    public HandsOnRequiredException(HandsOnTask task) {
        super("Not implemented yet: " + task + ". See docs/HANDS_ON.md.");
        this.task = task;
    }

    public HandsOnTask task() { return task; }
}
