package com.spliteasy.config;

import com.spliteasy.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guard the serialized financial contract after normal authorization, before exposing unlike units.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class LedgerResponseAdvice implements ResponseBodyAdvice<Object> {
  private final ObjectMapper mapper;

  @Override
  public boolean supports(
      MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter parameter,
      MediaType mediaType,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body != null) {
      JsonNode json = mapper.valueToTree(body);
      requireSafeIntegers(json);
      if (!"2".equals(request.getHeaders().getFirst(LedgerContract.HEADER))
          && containsNonUsd(json)) {
        throw new ConflictException(LedgerContract.UPGRADE);
      }
    }
    return body;
  }

  private void requireSafeIntegers(JsonNode value) {
    if (value.isObject()) {
      for (var field : value.properties()) {
        if (field.getKey().endsWith("Minor") && !field.getValue().isNull()) {
          long amount = field.getValue().longValue();
          if (!field.getValue().isIntegralNumber()
              || amount < -9_007_199_254_740_991L
              || amount > 9_007_199_254_740_991L) {
            throw new ConflictException(
                "This total exceeds the supported exact integer range. Use a smaller ledger scope.");
          }
        }
      }
    }
    if (value.isArray() || value.isObject())
      for (JsonNode child : value) requireSafeIntegers(child);
  }

  private boolean containsNonUsd(JsonNode value) {
    if (value.isObject()
        && value.hasNonNull("currency")
        && !"USD".equals(value.path("currency").asText())) return true;
    if ((value.isArray() || value.isObject()))
      for (JsonNode child : value) if (containsNonUsd(child)) return true;
    return false;
  }
}
