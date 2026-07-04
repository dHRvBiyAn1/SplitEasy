package com.spliteasy.service.split;

import com.spliteasy.dto.SplitInput;
import java.util.List;
import java.util.UUID;

/**
 * Input for a {@link SplitStrategy}. The service resolves group membership before
 * building this: for EQUAL it fills {@code participantIds}; for UNEQUAL/PERCENTAGE
 * it fills {@code splits}. A strategy reads whichever field it needs.
 */
public record SplitContext(
        long totalCents,
        UUID payerId,
        List<UUID> participantIds,
        List<SplitInput> splits) {}
