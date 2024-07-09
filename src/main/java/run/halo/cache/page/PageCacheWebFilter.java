package run.halo.cache.page;

import static java.nio.ByteBuffer.allocateDirect;
import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.security.AfterSecurityWebFilter;
import run.halo.app.theme.router.ModelConst;

@Slf4j
@Component
public class PageCacheWebFilter implements AfterSecurityWebFilter {

    public static final String CACHE_NAME = "page";

    private final Cache cache;

    private final ServerWebExchangeMatcher exchangeMatcher;

    public PageCacheWebFilter(CacheManager cacheManager) {
        this.cache = cacheManager.getCache(CACHE_NAME);

        var htmlMatcher = new MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML);
        htmlMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        var pathMatcher = ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/**");
        ServerWebExchangeMatcher cacheableMatcher = exchange -> {
            var cacheControl = exchange.getRequest().getHeaders().getCacheControl();
            if ("no-cache".equals(cacheControl)) {
                return MatchResult.notMatch();
            }
            return MatchResult.match();
        };
        ServerWebExchangeMatcher anonymousMatcher =
            exchange -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .filter(name -> !AnonymousUserConst.isAnonymousUser(name))
                .flatMap(name -> MatchResult.notMatch())
                .switchIfEmpty(Mono.defer(MatchResult::match));

        this.exchangeMatcher = new AndServerWebExchangeMatcher(
            htmlMatcher, pathMatcher, cacheableMatcher, anonymousMatcher
        );
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return this.exchangeMatcher.matches(exchange)
            .filter(MatchResult::isMatch)
            .switchIfEmpty(Mono.defer(() -> {
                log.debug("Skip caching for {} because not cacheable",
                    exchange.getRequest().getURI());
                return chain.filter(exchange).then(Mono.empty());
            }))
            .flatMap(matchResult -> {
                // anonymous authentication
                var cacheKey = generateCacheKey(exchange.getRequest());
                var cachedResponse = cache.get(cacheKey, CachedResponse.class);
                if (cachedResponse != null) {
                    log.debug("Retrieved cached response for {}", cacheKey);
                    // cache hit, then write the cached response
                    return writeCachedResponse(exchange.getResponse(), cachedResponse);
                }
                // decorate the ServerHttpResponse to cache the response
                var decoratedExchange = exchange.mutate()
                    .response(new CacheResponseDecorator(exchange, cacheKey))
                    .build();
                return chain.filter(decoratedExchange);
            });
    }

    private boolean responseCacheable(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        if (!MediaType.TEXT_HTML.equals(response.getHeaders().getContentType())) {
            return false;
        }
        var statusCode = response.getStatusCode();
        if (statusCode == null || !statusCode.isSameCodeAs(HttpStatus.OK)) {
            return false;
        }
        return exchange.getAttributeOrDefault(ModelConst.POWERED_BY_HALO_TEMPLATE_ENGINE, false);
    }

    private String generateCacheKey(ServerHttpRequest request) {
        return request.getURI().toASCIIString();
    }

    private Mono<Void> writeCachedResponse(ServerHttpResponse response,
        CachedResponse cachedResponse) {
        response.setStatusCode(cachedResponse.getStatusCode());
        response.getHeaders().clear();
        response.getHeaders().addAll(cachedResponse.getHeaders());
        response.getHeaders().setInstant("X-Halo-Cache-At", cachedResponse.getTimestamp());
        var body = Flux.fromIterable(cachedResponse.getBody())
            .map(byteBuffer -> response.bufferFactory().wrap(byteBuffer));
        return response.writeWith(body);
    }

    class CacheResponseDecorator extends ServerHttpResponseDecorator {

        private final ServerWebExchange exchange;

        private final String cacheKey;

        public CacheResponseDecorator(ServerWebExchange exchange, String cacheKey) {
            super(exchange.getResponse());
            this.exchange = exchange;
            this.cacheKey = cacheKey;
        }

        @Override
        @NonNull
        public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
            if (responseCacheable(exchange)) {
                log.debug("Caching response for {}", cacheKey);
                var response = getDelegate();

                var builder = new CachedResponse.CachedResponseBuilder();
                response.beforeCommit(
                    () -> Mono.fromRunnable(() -> {
                        var statusCode = getStatusCode();
                        if (statusCode != null && statusCode.is2xxSuccessful()) {
                            // we only cache response with 2xx status code
                            var headers = new HttpHeaders();
                            headers.addAll(getHeaders());
                            builder.statusCode(statusCode);
                            builder.timestamp(Instant.now());
                            builder.headers(headers);
                            cache.put(cacheKey, builder.build());
                        }
                    })
                );
                body = Flux.from(body)
                    .map(dataBuffer -> {
                        var byteBuffer = allocateDirect(dataBuffer.readableByteCount());
                        dataBuffer.toByteBuffer(byteBuffer);
                        DataBufferUtils.release(dataBuffer);
                        return byteBuffer.asReadOnlyBuffer();
                    })
                    .collectSortedList()
                    .doOnNext(builder::body)
                    .flatMapMany(Flux::fromIterable)
                    .map(byteBuffer -> response.bufferFactory().wrap(byteBuffer));
            }
            // write the response
            return super.writeWith(body);
        }
    }
}
