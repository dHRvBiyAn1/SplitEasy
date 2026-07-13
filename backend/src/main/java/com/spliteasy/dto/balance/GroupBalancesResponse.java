package com.spliteasy.dto.balance;

import java.util.List;
import java.util.UUID;

public record GroupBalancesResponse(UUID groupId, List<MemberBalance> balances) {}
