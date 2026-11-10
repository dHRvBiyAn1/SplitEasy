package com.spliteasy.service;

import com.spliteasy.exception.ConflictException;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Transactional create claims; callers authorize before invoking and own the transaction. */
@Component
@RequiredArgsConstructor
public class CreateOperations {
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public <T> T create(
      UUID actor,
      String kind,
      UUID scope,
      UUID operation,
      Object intent,
      Class<T> responseType,
      Consumer<T> authorizeReplay,
      Supplier<T> create) {
    if (operation == null) return create.get();
    String submitted = mapper.writeValueAsString(intent);
    int inserted =
        jdbc.sql(
                """
        INSERT INTO create_operations(actor_id,kind,scope_id,operation_id,intent)
        VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
        """)
            .params(actor, kind, scope, operation, submitted)
            .update();
    if (inserted == 0) {
      var stored =
          jdbc.sql(
                  """
          SELECT intent,response FROM create_operations
          WHERE actor_id=? AND kind=? AND scope_id=? AND operation_id=?
          """)
              .params(actor, kind, scope, operation)
              .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2)))
              .single();
      T response = mapper.treeToValue(receipt(kind, stored.response()), responseType);
      authorizeReplay.accept(response);
      if (!intent(kind, stored.intent()).equals(intent(kind, submitted))) {
        throw new ConflictException("Operation ID already used for a different " + kind);
      }
      com.spliteasy.config.ParticipantContract.requireReceipt(mapper.valueToTree(response));
      return response;
    }
    T response = create.get();
    jdbc.sql(
            """
        UPDATE create_operations SET response=?
        WHERE actor_id=? AND kind=? AND scope_id=? AND operation_id=?
        """)
        .params(mapper.writeValueAsString(response), actor, kind, scope, operation)
        .update();
    return response;
  }

  /** Normalize only the currency contract; every original intent field remains part of equality. */
  private tools.jackson.databind.JsonNode intent(String kind, String json) {
    var node = mapper.readTree(json);
    if (!"expense".equals(kind)) return node;
    var object = (tools.jackson.databind.node.ObjectNode) node;
    if (!object.hasNonNull("currency")) object.put("currency", "USD");
    if (!object.hasNonNull("amountMinor") && object.hasNonNull("amountCents")) {
      object.set("amountMinor", object.get("amountCents"));
    }
    object.remove("amountCents");
    return object;
  }

  /** Enrich the immutable stored USD snapshot, never the edited/deleted live resource. */
  private tools.jackson.databind.JsonNode receipt(String kind, String json) {
    var node = mapper.readTree(json);
    if ("expense".equals(kind) && !node.hasNonNull("currency")) {
      var object = (tools.jackson.databind.node.ObjectNode) node;
      object.put("currency", "USD");
      object.set("amountMinor", object.get("amountCents"));
      for (var share : object.path("participants")) {
        var participant = (tools.jackson.databind.node.ObjectNode) share;
        participant.put("currency", "USD");
        participant.set("shareMinor", participant.get("shareCents"));
      }
    }
    return node;
  }

  private record Stored(String intent, String response) {}
}
