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
    if (body != null
        && !"2".equals(request.getHeaders().getFirst(LedgerContract.HEADER))
        && containsNonUsd(mapper.valueToTree(body)))
      throw new ConflictException(LedgerContract.UPGRADE);
    return body;
  }

  private boolean containsNonUsd(JsonNode value) {
    if (value.isObject()
        && value.hasNonNull("currency")
        && !"USD".equals(value.path("currency").asText())) return true;
    if (value.isContainer()) for (JsonNode child : value) if (containsNonUsd(child)) return true;
    return false;
  }
}
