package com.spliteasy.dto.expense;

import java.time.Instant;

public record DeletedExpense(ExpenseResponse expense, Instant deletedAt) {}
