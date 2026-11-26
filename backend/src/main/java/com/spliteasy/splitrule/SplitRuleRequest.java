package com.spliteasy.splitrule;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param inputs EQUAL: the participant set (values ignored); PERCENTAGE: basis points summing to
 *     10000; WEIGHTS: positive units
 * @param expectedVersion required on PUT only
 */
public record SplitRuleRequest(
    @NotBlank @Size(max = 80) String name,
    @NotNull SplitType splitType,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid SplitInput> inputs,
    @PositiveOrZero Long expectedVersion) {}
