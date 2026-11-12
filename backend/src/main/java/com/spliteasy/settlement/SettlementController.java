package com.spliteasy.settlement;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.entity.LedgerCurrency;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/friends/{friendId}")
@RequiredArgsConstructor
public class SettlementController {
  private final SettlementService settlements;

  @GetMapping("/settlement-preview")
  public SettlementPreview preview(
      @CurrentUserId UUID owner,
      @PathVariable UUID friendId,
      @RequestParam LedgerCurrency currency) {
    return settlements.preview(owner, friendId, currency);
  }

  @PostMapping("/settlements")
  @ResponseStatus(HttpStatus.CREATED)
  public SettlementResponse commit(
      @CurrentUserId UUID owner,
      @PathVariable UUID friendId,
      @Valid @RequestBody CommitSettlementRequest request) {
    return settlements.commit(owner, friendId, request);
  }
}
