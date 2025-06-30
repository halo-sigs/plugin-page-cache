package run.halo.cache.page;

import static run.halo.cache.page.PageCacheWebFilter.CACHE_NAME;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author guqing
 * @since 1.0.0
 */
@Component
public class PageCachePlugin extends BasePlugin {

    private final CacheManager cacheManager;

    public PageCachePlugin(PluginContext pluginContext, CacheManager cacheManager) {
        super(pluginContext);
        this.cacheManager = cacheManager;
    }

    @Override
    public void stop() {
        var cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }
}
