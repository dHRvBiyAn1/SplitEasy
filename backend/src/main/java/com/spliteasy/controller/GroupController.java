package com.spliteasy.controller;

import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.GroupResponse;
import com.spliteasy.dto.group.GroupSummary;

import lombok.RequiredArgsConstructor;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.GroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;


    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(
            @CurrentUserId UUID userId,
            @Valid @RequestBody CreateGroupRequest request) {
        GroupResponse response = groupService.createGroup(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{groupId}/members")
    public GroupResponse addMember(
            @CurrentUserId UUID userId,
            @PathVariable UUID groupId,
            @Valid @RequestBody AddMemberRequest request) {
        return groupService.addMember(userId, groupId, request.email());
    }

    @GetMapping
    public List<GroupSummary> listMyGroups(@CurrentUserId UUID userId) {
        return groupService.listMyGroups(userId);
    }

    @GetMapping("/{groupId}")
    public GroupResponse getGroup(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
        return groupService.getGroup(userId, groupId);
    }
}
