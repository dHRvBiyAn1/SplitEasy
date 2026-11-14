package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.payment.PaymentResponse;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.service.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/payments")
@RequiredArgsConstructor
public class PaymentController {

  private final PaymentService paymentService;

  @PostMapping
  public ResponseEntity<PaymentResponse> recordPayment(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @Valid @RequestBody RecordPaymentRequest request) {
    PaymentResponse response = paymentService.recordPayment(userId, groupId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<PaymentResponse> listPayments(
      @CurrentUserId UUID userId, @PathVariable UUID groupId) {
    return paymentService.listPayments(userId, groupId);
  }

  @GetMapping("/deleted")
  public List<com.spliteasy.dto.payment.DeletedPayment> deleted(
      @CurrentUserId UUID userId, @PathVariable UUID groupId) {
    return paymentService.listDeleted(userId, groupId);
  }

  @GetMapping("/{paymentId}")
  public PaymentResponse getPayment(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID paymentId) {
    return paymentService.getPayment(userId, groupId, paymentId);
  }

  @GetMapping("/{paymentId}/history")
  public List<com.spliteasy.dto.payment.LedgerRevision<PaymentResponse>> history(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID paymentId) {
    return paymentService.getHistory(userId, groupId, paymentId);
  }

  @org.springframework.web.bind.annotation.PutMapping("/{paymentId}")
  public PaymentResponse correctPayment(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID paymentId,
      @Valid @RequestBody com.spliteasy.dto.payment.CorrectPaymentRequest request) {
    return paymentService.correctPayment(userId, groupId, paymentId, request);
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{paymentId}")
  @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePayment(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID paymentId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Long expectedVersion,
      @org.springframework.web.bind.annotation.RequestParam(required = false) UUID operationId) {
    paymentService.deletePayment(userId, groupId, paymentId, expectedVersion, operationId);
  }

  @PostMapping("/{paymentId}/restore")
  public PaymentResponse restorePayment(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID paymentId,
      @Valid @RequestBody com.spliteasy.dto.payment.RestorePaymentRequest request) {
    return paymentService.restorePayment(userId, groupId, paymentId, request);
  }
}
