package run.halo.cache.page;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import org.springdoc.core.fn.builders.apiresponse.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class CacheEndpoint implements CustomEndpoint {

    private final CacheManager cacheManager;

    public CacheEndpoint(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "CacheV1alpha1Console";
        return SpringdocRouteBuilder.route()
            .DELETE("/caches/page", this::evictCache, builder -> {
                builder
                    .tag(tag)
                    .operationId("EvictPageCache")
                    .description("Evict Page cache.")
                    .response(Builder.responseBuilder()
                        .responseCode(String.valueOf(NO_CONTENT.value())));
            })
            .build();
    }

    private Mono<ServerResponse> evictCache(ServerRequest request) {
        return Mono.fromRunnable(() -> {
            var cache = cacheManager.getCache(PageCacheWebFilter.CACHE_NAME);
            if (cache != null) {
                cache.invalidate();
            }
        }).then(ServerResponse.ok().build());
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.cache.halo.run", "v1alpha1");
    }
}
