package com.spliteasy.service;

import com.spliteasy.config.ParticipantContract;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.DeletedExpense;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.ExpenseSummary;
import com.spliteasy.dto.expense.RestoreExpenseRequest;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.GroupMembership;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.FriendRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.PersonalLedgerRepository;
import com.spliteasy.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-group expenses between an account and one other account, stored in the pair's hidden ledger
 * group so every expense rule, lock, version and history path applies unchanged. A saved-friend
 * bookmark authorizes creating the ledger; reading or changing it needs ledger membership only, so
 * removing the bookmark never revokes jointly owned money.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalLedgerService {
  private final PersonalLedgerRepository ledgers;
  private final FriendRepository friends;
  private final GroupRepository groups;
  private final GroupMembershipRepository memberships;
  private final UserRepository users;
  private final FinancialParticipantService participants;
  private final ExpenseService expenses;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<ExpenseSummary> list(UUID requester, UUID friend) {
    return ledger(requester, friend)
        .map(id -> expenses.listExpenses(requester, id))
        .orElse(List.of());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DeletedExpense> deleted(UUID requester, UUID friend) {
    return ledger(requester, friend)
        .map(id -> expenses.listDeleted(requester, id))
        .orElse(List.of());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ExpenseResponse get(UUID requester, UUID friend, UUID expense) {
    return expenses.getExpense(requester, existing(requester, friend), expense);
  }

  // One transaction: a rejected expense must not leave a newly created empty ledger behind.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse create(UUID requester, UUID friend, CreateExpenseRequest request) {
    UUID ledger = ledger(requester, friend).orElseGet(() -> open(requester, friend));
    return expenses.createExpense(requester, ledger, request);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse update(
      UUID requester, UUID friend, UUID expense, CreateExpenseRequest request) {
    return expenses.updateExpense(requester, existing(requester, friend), expense, request);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(
      UUID requester, UUID friend, UUID expense, Long expectedVersion, UUID operationId) {
    expenses.deleteExpense(
        requester, existing(requester, friend), expense, expectedVersion, operationId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse restore(
      UUID requester, UUID friend, UUID expense, RestoreExpenseRequest request) {
    return expenses.restoreExpense(requester, existing(requester, friend), expense, request);
  }

  /**
   * The pair's ledger, or empty when none exists yet and the requester has saved this friend. 403
   * otherwise, so nobody learns whether two other accounts share a ledger.
   */
  private Optional<UUID> ledger(UUID requester, UUID friend) {
    ParticipantContract.requireEnabled();
    if (friend.equals(requester) || participants.owned(requester).contains(friend))
      throw new BadRequestException("Choose another person for a personal expense.");
    var ledger = ledgers.find(requester, friend);
    if (ledger.isPresent() || friends.find(requester, friend).isPresent()) return ledger;
    if (ledgers.isVisibleGuest(friend, requester))
      throw new BadRequestException(
          "This guest must claim their participant and be saved as your friend before you can add personal expenses with them.");
    throw new ForbiddenException("Save this person as a friend to add personal expenses.");
  }

  private UUID existing(UUID requester, UUID friend) {
    return ledger(requester, friend).orElseThrow(() -> new NotFoundException("Expense not found"));
  }

  /** Creates the pair ledger; a concurrent first write wins the claim and this one reuses it. */
  private UUID open(UUID requester, UUID friend) {
    var creator = users.getReferenceById(requester);
    var account =
        users.findById(friend).orElseThrow(() -> new NotFoundException("Saved friend not found"));
    Group ledger = groups.saveAndFlush(Group.personalLedger(creator));
    if (!ledgers.claim(requester, friend, ledger.getId())) {
      groups.delete(ledger);
      groups.flush();
      return ledgers.find(requester, friend).orElseThrow();
    }
    memberships.saveAndFlush(new GroupMembership(ledger, creator));
    memberships.saveAndFlush(new GroupMembership(ledger, account));
    participants.activate(ledger.getId(), requester);
    participants.activate(ledger.getId(), friend);
    log.info("Personal ledger {} opened by account {}", ledger.getId(), requester);
    return ledger.getId();
  }
}
