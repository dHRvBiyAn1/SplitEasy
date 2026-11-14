package com.spliteasy.dto.history;

import java.util.List;

/** A bounded page; pass {@code nextCursor} back to continue, null when nothing is left. */
public record HistoryPage(List<HistoryItem> items, String nextCursor) {}
