package com.spliteasy.controller;

import com.spliteasy.dto.AddMemberRequest;
import com.spliteasy.dto.CreateGroupRequest;
import com.spliteasy.dto.GroupResponse;
import com.spliteasy.dto.GroupSummary;
import com.spliteasy.service.GroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateGroupRequest request) {
        GroupResponse response = groupService.createGroup(currentUserId(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{groupId}/members")
    public GroupResponse addMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID groupId,
            @Valid @RequestBody AddMemberRequest request) {
        return groupService.addMember(currentUserId(jwt), groupId, request.email());
    }

    @GetMapping
    public List<GroupSummary> listMyGroups(@AuthenticationPrincipal Jwt jwt) {
        return groupService.listMyGroups(currentUserId(jwt));
    }

    @GetMapping("/{groupId}")
    public GroupResponse getGroup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID groupId) {
        return groupService.getGroup(currentUserId(jwt), groupId);
    }

    private static UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
