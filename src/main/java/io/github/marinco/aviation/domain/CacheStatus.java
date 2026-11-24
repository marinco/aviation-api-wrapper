package io.github.marinco.aviation.domain;

/** How a successful lookup was answered; sent to clients in the {@code X-Cache} header. */
public enum CacheStatus {
    /** Fresh entry from the cache; the provider wasn't called. */
    HIT,
    /** Fetched from the provider just now. */
    MISS,
    /** The provider failed, so an older cached copy was served. */
    STALE
}
