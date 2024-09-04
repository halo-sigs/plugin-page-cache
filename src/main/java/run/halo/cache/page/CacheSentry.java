package run.halo.cache.page;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.event.post.PostUpdatedEvent;

/**
 * Sentry for evicting cache.
 */
@Slf4j
@Component
public class CacheSentry {

    private final Cache cache;

    public CacheSentry(CacheManager cacheManager) {
        this.cache = cacheManager.getCache(PageCacheWebFilter.CACHE_NAME);
    }

    @EventListener
    void onPostUpdated(PostUpdatedEvent event) {
        cache.clear();
        log.info("Received post updated event, and evicted page cache");
    }

}
