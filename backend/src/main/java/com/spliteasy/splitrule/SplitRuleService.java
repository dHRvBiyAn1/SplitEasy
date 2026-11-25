package com.spliteasy.splitrule;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.service.GroupWriteGuard;
import com.spliteasy.service.MembershipGuard;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Private (owner-only) ratio split rules; they only prefill a draft and never touch expenses. */
@Service
@RequiredArgsConstructor
public class SplitRuleService {
  private static final String COLUMNS =
      "id,group_id,name,split_type,inputs::text AS inputs,version,created_at,updated_at";
  private static final long BASIS_POINTS = 10_000;

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final MembershipGuard membership;
  private final GroupWriteGuard writeGuard;

  @Transactional(readOnly = true)
  public List<SplitRuleResponse> list(UUID userId, UUID groupId) {
    membership.requireMember(groupId, userId);
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM split_rules WHERE owner_id=? AND group_id=? AND deleted_at IS NULL"
                + " ORDER BY lower(name), id")
        .params(userId, groupId)
        .query(this::map)
        .list();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SplitRuleResponse create(UUID userId, UUID groupId, SplitRuleRequest request) {
    writeGuard.lockForMember(groupId, userId);
    List<SplitInput> inputs = validate(request);
    requireNameFree(userId, groupId, request.name(), null);
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO split_rules(id,owner_id,group_id,name,split_type,inputs)"
                + " VALUES (?,?,?,?,?,CAST(? AS jsonb))")
        .params(id, userId, groupId, request.name().trim(), request.splitType().name())
        .param(mapper.writeValueAsString(inputs))
        .update();
    return find(userId, groupId, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SplitRuleResponse update(UUID userId, UUID groupId, UUID id, SplitRuleRequest request) {
    if (request.expectedVersion() == null)
      throw new BadRequestException("expectedVersion is required");
    writeGuard.lockForMember(groupId, userId);
    List<SplitInput> inputs = validate(request);
    requireVersion(find(userId, groupId, id), request.expectedVersion());
    requireNameFree(userId, groupId, request.name(), id);
    jdbc.sql(
            "UPDATE split_rules SET name=?,split_type=?,inputs=CAST(? AS jsonb),version=version+1,"
                + "updated_at=clock_timestamp() WHERE id=?")
        .params(request.name().trim(), request.splitType().name())
        .param(mapper.writeValueAsString(inputs))
        .param(id)
        .update();
    return find(userId, groupId, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(UUID userId, UUID groupId, UUID id, long expectedVersion) {
    writeGuard.lockForMember(groupId, userId);
    requireVersion(find(userId, groupId, id), expectedVersion);
    jdbc.sql("UPDATE split_rules SET deleted_at=clock_timestamp() WHERE id=?").param(id).update();
  }

  private void requireVersion(SplitRuleResponse current, long expected) {
    if (current.version() != expected)
      throw new ConflictException("This rule changed — reload it before editing");
  }

  private void requireNameFree(UUID userId, UUID groupId, String name, UUID except) {
    boolean taken =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM split_rules WHERE owner_id=? AND group_id=?"
                    + " AND lower(name)=lower(?) AND deleted_at IS NULL AND id IS DISTINCT FROM ?)")
            .params(userId, groupId, name.trim(), except)
            .query(Boolean.class)
            .single();
    if (taken) throw new ConflictException("You already have a split rule with that name");
  }

  /** Only ratios are saveable: amounts (exact, adjustments) would not survive an amount change. */
  private List<SplitInput> validate(SplitRuleRequest request) {
    SplitType type = request.splitType();
    if (type != SplitType.EQUAL && type != SplitType.PERCENTAGE && type != SplitType.WEIGHTS)
      throw new BadRequestException("Only equal, percentage and weight splits can be saved");
    if (request.name().isBlank()) throw new BadRequestException("name is required");
    var users = new HashSet<UUID>();
    for (SplitInput in : request.inputs())
      if (!users.add(in.userId())) throw new BadRequestException("Each person may appear once");
    return switch (type) {
      case EQUAL -> request.inputs().stream().map(in -> new SplitInput(in.userId(), 1)).toList();
      case PERCENTAGE -> {
        long sum = 0;
        for (SplitInput in : request.inputs()) {
          if (in.value() < 0 || in.value() > BASIS_POINTS)
            throw new BadRequestException("Percentages must be between 0 and 100");
          sum += in.value();
        }
        if (sum != BASIS_POINTS) throw new BadRequestException("Percentages must total 100");
        yield request.inputs();
      }
      default -> {
        for (SplitInput in : request.inputs())
          if (in.value() < 1) throw new BadRequestException("Weights must be positive");
        yield request.inputs();
      }
    };
  }

  private SplitRuleResponse find(UUID userId, UUID groupId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM split_rules WHERE id=? AND owner_id=? AND group_id=? AND deleted_at IS NULL")
        .params(id, userId, groupId)
        .query(this::map)
        .optional()
        .orElseThrow(() -> new NotFoundException("Split rule not found"));
  }

  private SplitRuleResponse map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new SplitRuleResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getString("name"),
        SplitType.valueOf(rs.getString("split_type")),
        mapper.readValue(rs.getString("inputs"), new TypeReference<List<SplitInput>>() {}),
        rs.getLong("version"),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
  }
}
