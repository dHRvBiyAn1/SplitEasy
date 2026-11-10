package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.payment.LedgerRevision;
import com.spliteasy.dto.payment.RecordRefundRequest;
import com.spliteasy.dto.payment.RefundResponse;
import com.spliteasy.service.RefundService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/expenses/{expenseId}/refunds")
@RequiredArgsConstructor
public class RefundController {

  private final RefundService refundService;

  @PostMapping
  public ResponseEntity<RefundResponse> recordRefund(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody RecordRefundRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(refundService.recordRefund(userId, groupId, expenseId, request));
  }

  @GetMapping
  public List<RefundResponse> listRefunds(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID expenseId) {
    return refundService.listRefunds(userId, groupId, expenseId);
  }

  @GetMapping("/{refundId}/history")
  public List<LedgerRevision<RefundResponse>> history(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @PathVariable UUID refundId) {
    return refundService.getHistory(userId, groupId, expenseId, refundId);
  }

  @DeleteMapping("/{refundId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRefund(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @PathVariable UUID refundId,
      @RequestParam(required = false) Long expectedVersion) {
    refundService.deleteRefund(userId, groupId, expenseId, refundId, expectedVersion);
  }
}
