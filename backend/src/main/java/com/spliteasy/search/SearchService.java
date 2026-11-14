package com.spliteasy.search;

import com.spliteasy.dto.history.HistoryItem;
import com.spliteasy.dto.history.HistoryPage;
import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.FriendRepository;
import com.spliteasy.repository.PersonalLedgerRepository;
import com.spliteasy.search.SearchQuery.Criteria;
import com.spliteasy.search.SearchQuery.Cursor;
import com.spliteasy.service.MembershipGuard;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** P01-A search: validation, the same scope authorization as H05 history, keyset paging. */
@Service
@RequiredArgsConstructor
public class SearchService {
  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 100;

  private final SearchQuery query;
  private final MembershipGuard membershipGuard;
  private final FriendRepository friends;
  private final PersonalLedgerRepository personalLedgers;

  public record Request(
      String q,
      UUID groupId,
      UUID friendId,
      LocalDate from,
      LocalDate to,
      String currency,
      Long minAmount,
      Long maxAmount,
      String cursor,
      Integer limit) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public HistoryPage search(UUID viewer, Request r) {
    int size = r.limit() == null ? DEFAULT_LIMIT : r.limit();
    if (size < 1 || size > MAX_LIMIT)
      throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
    if (r.from() != null && r.to() != null && r.from().isAfter(r.to()))
      throw new BadRequestException("from must not be after to");
    // Amounts of different currencies are never comparable, so a bound needs its currency.
    if ((r.minAmount() != null || r.maxAmount() != null)
        && (r.currency() == null || r.currency().isBlank()))
      throw new BadRequestException("minAmount and maxAmount require a currency");
    if (r.minAmount() != null && r.maxAmount() != null && r.minAmount() > r.maxAmount())
      throw new BadRequestException("minAmount must not exceed maxAmount");
    authorize(viewer, r);
    String text = r.q() == null || r.q().isBlank() ? null : r.q().trim();
    var criteria =
        new Criteria(
            r.groupId(),
            r.friendId(),
            text,
            r.from(),
            r.to(),
            currency(r.currency()),
            r.minAmount(),
            r.maxAmount());
    // One extra row tells whether another page exists without a count query.
    List<HistoryItem> rows = query.search(viewer, criteria, decode(r.cursor()), size + 1);
    if (rows.size() <= size) return new HistoryPage(rows, null);
    List<HistoryItem> items = List.copyOf(rows.subList(0, size));
    HistoryItem last = items.get(size - 1);
    return new HistoryPage(items, encode(last.financialDate(), last.id()));
  }

  private void authorize(UUID viewer, Request r) {
    if (r.groupId() != null) {
      membershipGuard.requireMember(r.groupId(), viewer);
      // A personal ledger is not a group: read as absent, like every other group-scoped route.
      if (personalLedgers.isMemberOfPersonal(r.groupId(), viewer))
        throw new NotFoundException("Group not found");
    }
    if (r.friendId() != null && friends.find(viewer, r.friendId()).isEmpty())
      throw new NotFoundException("Saved friend not found");
  }

  private static LedgerCurrency currency(String code) {
    if (code == null || code.isBlank()) return null;
    try {
      return LedgerCurrency.valueOf(code.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unsupported currency");
    }
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
      throw new BadRequestException("Invalid search cursor");
    }
  }
}
