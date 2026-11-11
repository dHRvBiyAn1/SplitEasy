package com.spliteasy.service;

import com.spliteasy.dto.history.HistoryFilter;
import com.spliteasy.dto.history.HistoryItem;
import com.spliteasy.dto.history.HistoryPage;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.FriendRepository;
import com.spliteasy.repository.HistoryQuery;
import com.spliteasy.repository.HistoryQuery.Cursor;
import com.spliteasy.repository.PersonalLedgerRepository;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** H05 bounded history: authorization, page bounds and the opaque keyset cursor. */
@Service
@RequiredArgsConstructor
public class HistoryService {
  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 100;

  private final HistoryQuery query;
  private final MembershipGuard membershipGuard;
  private final FriendRepository friends;
  private final PersonalLedgerRepository personalLedgers;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public HistoryPage page(UUID viewer, HistoryFilter filter, String cursor, Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT)
      throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
    authorize(viewer, filter);
    // One extra row tells whether another page exists without a count query.
    List<HistoryItem> rows = query.page(viewer, filter, decode(cursor), size + 1);
    if (rows.size() <= size) return new HistoryPage(rows, null);
    List<HistoryItem> items = List.copyOf(rows.subList(0, size));
    HistoryItem last = items.get(size - 1);
    return new HistoryPage(items, encode(last.financialDate(), last.id()));
  }

  /** Streams every matching row through the cursor; no page size, but never buffered in memory. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public void export(UUID viewer, HistoryFilter filter, Supplier<Writer> out) {
    authorize(viewer, filter);
    var csv = new HistoryCsvWriter(out.get());
    csv.header();
    query.export(viewer, filter, csv::row);
  }

  void authorize(UUID viewer, HistoryFilter filter) {
    if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to()))
      throw new BadRequestException("from must not be after to");
    if (filter.groupId() != null) {
      membershipGuard.requireMember(filter.groupId(), viewer);
      // A personal ledger is not a group: read as absent, like every other group-scoped route.
      if (personalLedgers.isMemberOfPersonal(filter.groupId(), viewer))
        throw new NotFoundException("Group not found");
    }
    if (filter.friendId() != null && friends.find(viewer, filter.friendId()).isEmpty())
      throw new NotFoundException("Saved friend not found");
  }

  static String encode(LocalDate date, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((date + "/" + id).getBytes(StandardCharsets.UTF_8));
  }

  static Cursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("/", 2);
      return new Cursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid history cursor");
    }
  }
}
