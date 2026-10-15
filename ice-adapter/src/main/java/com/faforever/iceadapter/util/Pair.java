package com.faforever.iceadapter.util;

/**
 * A generic pair of two values.
 *
 * @param first  the first element
 * @param second the second element
 * @param <A>    type of first element
 * @param <B>    type of second element
 */
public record Pair<A, B>(A first, B second) {}
