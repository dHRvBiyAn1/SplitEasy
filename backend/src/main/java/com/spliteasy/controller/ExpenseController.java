package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.DeletedExpense;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.ExpenseRevision;
import com.spliteasy.dto.expense.ExpenseSummary;
import com.spliteasy.dto.expense.RestoreExpenseRequest;
import com.spliteasy.service.ExpenseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

  private final ExpenseService expenseService;

  @PostMapping
  public ResponseEntity<ExpenseResponse> createExpense(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @Valid @RequestBody CreateExpenseRequest request) {
    ExpenseResponse response = expenseService.createExpense(userId, groupId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<ExpenseSummary> listExpenses(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
    return expenseService.listExpenses(userId, groupId);
  }

  @GetMapping("/{expenseId}")
  public ExpenseResponse getExpense(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID expenseId) {
    return expenseService.getExpense(userId, groupId, expenseId);
  }

  @GetMapping("/deleted")
  public List<DeletedExpense> deleted(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
    return expenseService.listDeleted(userId, groupId);
  }

  @GetMapping("/{expenseId}/history")
  public List<ExpenseRevision> history(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID expenseId) {
    return expenseService.getHistory(userId, groupId, expenseId);
  }

  @PostMapping("/{expenseId}/restore")
  public ExpenseResponse restore(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody RestoreExpenseRequest request) {
    return expenseService.restoreExpense(userId, groupId, expenseId, request);
  }

  @PutMapping("/{expenseId}")
  public ExpenseResponse updateExpense(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody CreateExpenseRequest request) {
    return expenseService.updateExpense(userId, groupId, expenseId, request);
  }

  @DeleteMapping("/{expenseId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteExpense(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @RequestParam(required = false) Long expectedVersion,
      @RequestParam(required = false) UUID operationId) {
    expenseService.deleteExpense(userId, groupId, expenseId, expectedVersion, operationId);
  }
}
