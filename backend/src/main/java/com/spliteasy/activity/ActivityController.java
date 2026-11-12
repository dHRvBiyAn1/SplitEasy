package com.spliteasy.activity;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.PersonalLedgerRepository;
import com.spliteasy.service.MembershipGuard;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {
  private final ActivityQuery query;
  private final MembershipGuard membershipGuard;
  private final PersonalLedgerRepository personalLedgers;

  @GetMapping
  @Transactional(readOnly = true)
  public ActivityPage page(
      @CurrentUserId UUID userId,
      @RequestParam(required = false) UUID groupId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    if (groupId != null) {
      membershipGuard.requireMember(groupId, userId);
      // A personal ledger is not a group: read as absent, like history.
      if (personalLedgers.isMemberOfPersonal(groupId, userId))
        throw new NotFoundException("Group not found");
    }
    int size = ActivityQuery.clamp(limit);
    List<ActivityItem> rows = query.feed(userId, groupId, ActivityQuery.cursor(cursor), size);
    return page(rows, size);
  }

  static ActivityPage page(List<ActivityItem> rows, int size) {
    boolean more = rows.size() > size;
    List<ActivityItem> items = more ? rows.subList(0, size) : rows;
    return new ActivityPage(items, more ? String.valueOf(items.get(size - 1).seq()) : null);
  }
}
