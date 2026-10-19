package com.spliteasy.service;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.UserRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class FinancialParticipantService {
  private final GroupMembershipRepository membershipRepository;
  private final UserRepository userRepository;

  Map<UUID, UserSummary> resolve(UUID groupId, Collection<UUID> ledgerUserIds) {
    var ids = new HashSet<>(membershipRepository.findUserIdsByGroupId(groupId));
    ids.addAll(ledgerUserIds);
    Map<UUID, UserSummary> people =
        userRepository.findAllById(ids).stream()
            .map(UserSummary::from)
            .collect(Collectors.toMap(UserSummary::id, user -> user));
    if (people.size() != ids.size()) {
      throw new IllegalStateException("Financial participant references a missing user");
    }
    return people;
  }
}
