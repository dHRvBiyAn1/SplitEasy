package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.GroupResponse;
import com.spliteasy.dto.group.UpdateGroupRequest;
import com.spliteasy.entity.GroupType;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.service.GroupService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real PATCH requests and PostgreSQL commits; the spy only orders the two group reads. */
@AutoConfigureMockMvc
class GroupPatchConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @MockitoSpyBean GroupRepository groupRepository;
  @Autowired GroupService groupService;

  @ParameterizedTest(name = "{0} commits after {1}, preserving both partial edits")
  @CsvSource({
    "NAME, SIMPLIFY", "SIMPLIFY, NAME",
    "NAME, TYPE", "TYPE, NAME",
    "SIMPLIFY, TYPE", "TYPE, SIMPLIFY"
  })
  void disjointPatchesPreserveBothChanges(String last, String first) throws Exception {
    Fixture f = fixture();

    commitAfterConcurrentPatch(f, change(last), change(first));

    GroupResponse fresh = groupService.getGroup(f.owner().user().id(), f.groupId());
    assertThat(fresh.name())
        .isEqualTo(last.equals("NAME") || first.equals("NAME") ? "Renamed" : "Original");
    assertThat(fresh.type())
        .isEqualTo(last.equals("TYPE") || first.equals("TYPE") ? GroupType.TRIP : GroupType.OTHER);
    assertThat(fresh.simplifyDebts())
        .isEqualTo(!(last.equals("SIMPLIFY") || first.equals("SIMPLIFY")));
  }

  @Test
  void sameFieldChangesKeepTheLastCommittingDirtyWrite() throws Exception {
    Fixture f = fixture();

    commitAfterConcurrentPatch(
        f, new UpdateGroupRequest("Last", null, null), new UpdateGroupRequest("First", null, null));

    assertThat(groupService.getGroup(f.owner().user().id(), f.groupId()).name()).isEqualTo("Last");
  }

  @Test
  void assigningTheLoadedValueDoesNotUndoAConcurrentChange() throws Exception {
    Fixture f = fixture();

    commitAfterConcurrentPatch(
        f,
        new UpdateGroupRequest("Original", null, null),
        new UpdateGroupRequest("Changed", null, null));

    assertThat(groupService.getGroup(f.owner().user().id(), f.groupId()).name())
        .isEqualTo("Changed");
  }

  private void commitAfterConcurrentPatch(
      Fixture f, UpdateGroupRequest last, UpdateGroupRequest first) throws Exception {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    AtomicBoolean started = new AtomicBoolean();
    AtomicBoolean committed = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Object group =
                  mockingDetails(invocation.getMock())
                      .getMockCreationSettings()
                      .getDefaultAnswer()
                      .answer(invocation);
              if (started.compareAndSet(false, true)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                // Pause the first request after its real read, then commit the other request on
                // another connection before the stale request resumes. No test transaction/sleeps.
                CompletableFuture.runAsync(
                        () -> {
                          try {
                            patchGroup(f.member(), f.groupId(), first);
                          } catch (Exception e) {
                            throw new CompletionException(e);
                          }
                        })
                    .get(20, TimeUnit.SECONDS);
                committed.set(true);
              }
              return group;
            })
        .when(groupRepository)
        .findById(f.groupId());

    patchGroup(f.owner(), f.groupId(), last);
    assertThat(committed).isTrue();
  }

  private void patchGroup(AuthResponse user, UUID groupId, UpdateGroupRequest request)
      throws Exception {
    mockMvc
        .perform(
            patch("/api/groups/" + groupId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
        .andExpect(status().isOk());
  }

  private UpdateGroupRequest change(String field) {
    return switch (field) {
      case "NAME" -> new UpdateGroupRequest("Renamed", null, null);
      case "TYPE" -> new UpdateGroupRequest(null, GroupType.TRIP, null);
      case "SIMPLIFY" -> new UpdateGroupRequest(null, null, false);
      default -> throw new IllegalArgumentException(field);
    };
  }

  private Fixture fixture() throws Exception {
    String id = UUID.randomUUID().toString();
    AuthResponse owner = register(id + "-owner@example.com", "password123", "Owner");
    AuthResponse member = register(id + "-member@example.com", "password123", "Member");
    UUID groupId =
        groupService.createGroup(owner.user().id(), new CreateGroupRequest("Original")).id();
    groupService.addMember(owner.user().id(), groupId, member.user().email());
    return new Fixture(owner, member, groupId);
  }

  private record Fixture(AuthResponse owner, AuthResponse member, UUID groupId) {}
}
