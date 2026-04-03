package run.halo.cache.page;

import org.jspecify.annotations.Nullable;

/**
 * Cache properties.
 *
 * @param alwaysCache indicates whether the current request will be always cache.
 * @author johnniang
 * @since 1.5.0
 */
record CacheProperties(
    @Nullable Boolean alwaysCache
) {
}
