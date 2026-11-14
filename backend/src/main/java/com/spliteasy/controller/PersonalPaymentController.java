package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.payment.CorrectPaymentRequest;
import com.spliteasy.dto.payment.DeletedPayment;
import com.spliteasy.dto.payment.LedgerRevision;
import com.spliteasy.dto.payment.PaymentResponse;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.dto.payment.RestorePaymentRequest;
import com.spliteasy.service.PersonalLedgerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/friends/{friendId}/payments")
@RequiredArgsConstructor
public class PersonalPaymentController {
  private final PersonalLedgerService personal;

  @GetMapping
  public List<PaymentResponse> list(@CurrentUserId UUID userId, @PathVariable UUID friendId) {
    return personal.listPayments(userId, friendId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentResponse record(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @Valid @RequestBody RecordPaymentRequest request) {
    return personal.recordPayment(userId, friendId, request);
  }

  @GetMapping("/deleted")
  public List<DeletedPayment> deleted(@CurrentUserId UUID userId, @PathVariable UUID friendId) {
    return personal.deletedPayments(userId, friendId);
  }

  @GetMapping("/{paymentId}")
  public PaymentResponse get(
      @CurrentUserId UUID userId, @PathVariable UUID friendId, @PathVariable UUID paymentId) {
    return personal.getPayment(userId, friendId, paymentId);
  }

  @GetMapping("/{paymentId}/history")
  public List<LedgerRevision<PaymentResponse>> history(
      @CurrentUserId UUID userId, @PathVariable UUID friendId, @PathVariable UUID paymentId) {
    return personal.paymentHistory(userId, friendId, paymentId);
  }

  @PutMapping("/{paymentId}")
  public PaymentResponse correct(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @PathVariable UUID paymentId,
      @Valid @RequestBody CorrectPaymentRequest request) {
    return personal.correctPayment(userId, friendId, paymentId, request);
  }

  @DeleteMapping("/{paymentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @PathVariable UUID paymentId,
      @RequestParam(required = false) Long expectedVersion,
      @RequestParam(required = false) UUID operationId) {
    personal.deletePayment(userId, friendId, paymentId, expectedVersion, operationId);
  }

  @PostMapping("/{paymentId}/restore")
  public PaymentResponse restore(
      @CurrentUserId UUID userId,
      @PathVariable UUID friendId,
      @PathVariable UUID paymentId,
      @Valid @RequestBody RestorePaymentRequest request) {
    return personal.restorePayment(userId, friendId, paymentId, request);
  }
}
