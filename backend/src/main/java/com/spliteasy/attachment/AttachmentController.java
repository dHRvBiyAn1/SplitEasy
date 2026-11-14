package com.spliteasy.attachment;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.PersonalLedgerService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Group routes bind {@code groupId} (the personal-ledger guard needs it); friend routes mirror
 * them.
 */
@RestController
@RequiredArgsConstructor
public class AttachmentController {
  private final AttachmentService service;
  private final PersonalLedgerService personal;

  private static final String GROUP = "/api/groups/{groupId}/expenses/{expenseId}/attachments";
  private static final String FRIEND = "/api/friends/{friendId}/expenses/{expenseId}/attachments";

  @GetMapping(GROUP)
  public List<AttachmentResponse> list(
      @CurrentUserId UUID user, @PathVariable UUID groupId, @PathVariable UUID expenseId) {
    return service.list(user, groupId, expenseId);
  }

  @PostMapping(value = GROUP, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AttachmentResponse upload(
      @CurrentUserId UUID user,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @RequestParam("file") MultipartFile file) {
    return service.upload(user, groupId, expenseId, file);
  }

  @GetMapping(GROUP + "/{id}")
  public ResponseEntity<byte[]> download(
      @CurrentUserId UUID user,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @PathVariable UUID id) {
    return respond(service.download(user, groupId, expenseId, id));
  }

  @DeleteMapping(GROUP + "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @CurrentUserId UUID user,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @PathVariable UUID id) {
    service.delete(user, groupId, expenseId, id);
  }

  @GetMapping(FRIEND)
  public List<AttachmentResponse> listPersonal(
      @CurrentUserId UUID user, @PathVariable UUID friendId, @PathVariable UUID expenseId) {
    return service.list(user, ledger(user, friendId, expenseId), expenseId);
  }

  @PostMapping(value = FRIEND, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AttachmentResponse uploadPersonal(
      @CurrentUserId UUID user,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @RequestParam("file") MultipartFile file) {
    return service.upload(user, ledger(user, friendId, expenseId), expenseId, file);
  }

  @GetMapping(FRIEND + "/{id}")
  public ResponseEntity<byte[]> downloadPersonal(
      @CurrentUserId UUID user,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @PathVariable UUID id) {
    return respond(service.download(user, ledger(user, friendId, expenseId), expenseId, id));
  }

  @DeleteMapping(FRIEND + "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePersonal(
      @CurrentUserId UUID user,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @PathVariable UUID id) {
    service.delete(user, ledger(user, friendId, expenseId), expenseId, id);
  }

  /** Stored type only, forced download, no sniffing, never cached by shared caches. */
  private static ResponseEntity<byte[]> respond(AttachmentService.Download d) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(d.contentType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(d.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore().cachePrivate())
        .body(d.content());
  }

  private UUID ledger(UUID user, UUID friendId, UUID expenseId) {
    return personal.get(user, friendId, expenseId).groupId();
  }
}
