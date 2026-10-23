package dev.healstore.common.web;

import dev.healstore.common.core.HandsOnRequiredException;
import java.io.FileNotFoundException;
import java.nio.file.FileAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(HandsOnRequiredException.class)
    ProblemDetail unfinished(HandsOnRequiredException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, exception.getMessage());
        problem.setTitle("This part isn't implemented yet");
        problem.setProperty("code", "HANDS_ON_REQUIRED");
        problem.setProperty("task", exception.task());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(FileAlreadyExistsException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "That shard already exists. Overwriting it isn't supported yet.");
    }

    @ExceptionHandler(FileNotFoundException.class)
    ProblemDetail missing() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Shard does not exist.");
    }
}
