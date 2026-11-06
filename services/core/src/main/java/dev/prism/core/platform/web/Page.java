package dev.prism.core.platform.web;

import java.util.List;

/** A page of results. {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, String nextCursor) {}
