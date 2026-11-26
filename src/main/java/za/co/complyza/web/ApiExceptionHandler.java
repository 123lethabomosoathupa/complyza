package za.co.complyza.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unprocessable(IllegalStateException e) {
        log.warn("Request could not be processed: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail upstream(RuntimeException e) {
        log.error("Request failed", e);
        String all = allMessages(e).toLowerCase();
        if (all.contains("429") || all.contains("quota") || all.contains("rate limit")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                    "The AI service is rate limited. Please wait a minute and try again.");
        }
        if (all.contains("503") || all.contains("high demand") || all.contains("overloaded")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI model is busy right now. Please try again in a moment.");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The request could not be completed. Please try again.");
    }

    private static String allMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 2000; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getMessage()).append(' ');
        }
        return sb.toString();
    }
}