package run.halo.cache.page;

import static java.nio.ByteBuffer.allocate;
import static org.springframework.http.HttpHeaders.readOnlyHttpHeaders;
import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * The value for cache control value
     */
    public static final String CACHE_CONTROL_VALUE = "max-age=3, s-maxage=120, must-revalidate";

    public static final String HALO_CACHE_AT_HEADER = "X-Halo-Cache-At";

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
                    return writeCachedResponse(exchange, cachedResponse);
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

    private static String generateCacheKey(ServerHttpRequest request) {
        return request.getURI().toASCIIString();
    }

    private static Mono<Void> writeCachedResponse(ServerWebExchange exchange,
        CachedResponse cachedResponse) {
        var response = exchange.getResponse();
        if (exchange.checkNotModified(cachedResponse.getTimestamp())) {
            // set cache control
            setCacheControl(response);
            return exchange.getResponse().setComplete();
        }

        response.beforeCommit(() -> Mono.fromRunnable(() -> {
            response.setStatusCode(cachedResponse.getStatusCode());
            cachedResponse.getHeaders().forEach((key, values) -> {
                if (response.getHeaders().get(key) == null) {
                    response.getHeaders().put(key, values);
                }
            });
            setCacheControl(response);
            response.getHeaders().setInstant(HALO_CACHE_AT_HEADER, cachedResponse.getTimestamp());
        }));

        var body = Flux.fromIterable(cachedResponse.getBody())
            .map(byteBuffer -> response.bufferFactory().wrap(byteBuffer));
        return response.writeAndFlushWith(Flux.from(body).window(1));
    }

    private static void setCacheControl(ServerHttpResponse response) {
        response.getHeaders().setCacheControl(CACHE_CONTROL_VALUE);
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
        public Mono<Void> writeAndFlushWith(
            @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body) {
            if (responseCacheable(exchange)) {
                log.debug("Caching response for {}", cacheKey);
                var builder = new CachedResponse.CachedResponseBuilder();
                var headers = new HttpHeaders();
                getHeaders().forEach(headers::addAll);
                headers.setCacheControl((String) null);
                builder.statusCode(getStatusCode());
                builder.timestamp(Instant.now());
                builder.headers(readOnlyHttpHeaders(headers));
                var bodyCopies = new ArrayList<ByteBuffer>();
                if (body instanceof Flux<? extends Publisher<? extends DataBuffer>> fluxBody) {
                    return fluxBody.concatMap(content -> copyBody(content, bodyCopies))
                        .window(1)
                        .doOnComplete(putCache(builder, bodyCopies))
                        .then(Mono.defer(
                            () -> writeCachedResponse(exchange, builder.build())
                        ));
                } else if (body instanceof Mono<? extends Publisher<? extends DataBuffer>> monoBody) {
                    return monoBody.flatMapMany(content -> copyBody(content, bodyCopies))
                        .window(1)
                        .doOnComplete(putCache(builder, bodyCopies))
                        .then(Mono.defer(
                            () -> writeCachedResponse(exchange, builder.build())
                        ));
                }
            }
            return super.writeAndFlushWith(body);
        }

        @Override
        @NonNull
        public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
            if (responseCacheable(exchange)) {
                log.info("Caching response for {}", cacheKey);
                var builder = new CachedResponse.CachedResponseBuilder();
                var headers = new HttpHeaders();
                getHeaders().forEach(headers::addAll);
                builder.statusCode(getStatusCode());
                builder.timestamp(Instant.now());
                builder.headers(readOnlyHttpHeaders(headers));
                var bodyCopies = new ArrayList<ByteBuffer>();
                return copyBody(body, bodyCopies)
                    .doOnComplete(putCache(builder, bodyCopies))
                    .then(Mono.defer(
                        () -> writeCachedResponse(exchange, builder.build())
                    ));
            }
            // write the response
            return super.writeWith(body);
        }

        private Runnable putCache(CachedResponse.CachedResponseBuilder builder,
            List<ByteBuffer> bodyCopies) {
            return () -> {
                builder.body(List.copyOf(bodyCopies));
                cache.put(cacheKey, builder.build());
                log.info("Cached response for {}", cacheKey);
            };
        }

        private static Flux<? extends DataBuffer> copyBody(Publisher<? extends DataBuffer> body,
            List<ByteBuffer> buffers) {
            return Flux.from(body)
                .doOnNext(dataBuffer -> {
                    try {
                        var byteBuffer = allocate(dataBuffer.readableByteCount());
                        dataBuffer.toByteBuffer(byteBuffer);
                        buffers.add(byteBuffer.asReadOnlyBuffer());
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                });
        }
    }
}
