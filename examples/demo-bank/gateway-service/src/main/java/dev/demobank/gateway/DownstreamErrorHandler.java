package dev.demobank.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Passes downstream 4xx/5xx through unchanged and maps unreachable services to 502. */
@RestControllerAdvice
class DownstreamErrorHandler {

  @ExceptionHandler(RestClientResponseException.class)
  ProblemDetail downstreamError(RestClientResponseException e) {
    return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getStatusText());
  }

  @ExceptionHandler(ResourceAccessException.class)
  ProblemDetail downstreamUnreachable(ResourceAccessException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Downstream service unreachable");
  }
}
