package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.DeletedExpense;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.ExpenseSummary;
import com.spliteasy.dto.expense.RestoreExpenseRequest;
import com.spliteasy.service.PersonalLedgerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/friends/{friendId}/expenses")
@RequiredArgsConstructor
public class PersonalLedgerController {
  private final PersonalLedgerService personal;

  @GetMapping
  public List<ExpenseSummary> list(@CurrentUserId UUID userId, @PathVariable UUID friendId) {
    return personal.list(userId, friendId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ExpenseResponse create(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @Valid @RequestBody CreateExpenseRequest request) {
    return personal.create(userId, friendId, request);
  }

  @GetMapping("/deleted")
  public List<DeletedExpense> deleted(@CurrentUserId UUID userId, @PathVariable UUID friendId) {
    return personal.deleted(userId, friendId);
  }

  @GetMapping("/{expenseId}")
  public ExpenseResponse get(
      @CurrentUserId UUID userId, @PathVariable UUID friendId, @PathVariable UUID expenseId) {
    return personal.get(userId, friendId, expenseId);
  }

  @PutMapping("/{expenseId}")
  public ExpenseResponse update(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody CreateExpenseRequest request) {
    return personal.update(userId, friendId, expenseId, request);
  }

  @DeleteMapping("/{expenseId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @RequestParam(required = false) Long expectedVersion,
      @RequestParam(required = false) UUID operationId) {
    personal.delete(userId, friendId, expenseId, expectedVersion, operationId);
  }

  @PostMapping("/{expenseId}/restore")
  public ExpenseResponse restore(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody RestoreExpenseRequest request) {
    return personal.restore(userId, friendId, expenseId, request);
  }
}
