package com.spliteasy.dto.balance;

import com.spliteasy.dto.common.UserSummary;

import java.util.UUID;

/**
 * One suggested settle-up between the requesting user and a counterparty within a single group
 * (a debt-simplification transfer involving "me"). Positive {@code netCents} = they owe you (you
 * are the payee); negative = you owe them (you are the payer). Powers the global settle-up modal:
 * each row prefills a payment in {@code groupId} for {@code |netCents|}.
 */
public record Settlement(UUID groupId, String groupName, UserSummary counterparty, long netCents) {}
