package run.halo.cache.page;

import static java.nio.ByteBuffer.allocateDirect;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;

import java.time.Instant;
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
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;
import run.halo.app.theme.router.ModelConst;

@Slf4j
@Component
public class PageCacheWebFilter implements AdditionalWebFilter {

    public static final String REQUEST_TO_CACHE = "RequestCacheWebFilterToCache";

    public static final String CACHE_NAME = "page";

    private final Cache cache;

    private final ServerSecurityContextRepository serverSecurityContextRepository;

    public PageCacheWebFilter(CacheManager cacheManager,
        ServerSecurityContextRepository serverSecurityContextRepository) {
        this.cache = cacheManager.getCache(CACHE_NAME);
        this.serverSecurityContextRepository = serverSecurityContextRepository;
    }

    private static boolean hasRequestBody(ServerHttpRequest request) {
        return request.getHeaders().getContentLength() > 0;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return serverSecurityContextRepository.load(exchange)
            .switchIfEmpty(Mono.defer(() -> {
                var cacheKey = generateCacheKey(exchange.getRequest());
                var cachedResponse = cache.get(cacheKey, CachedResponse.class);
                if (cachedResponse != null) {
                    // cache hit, then write the cached response
                    return writeCachedResponse(exchange.getResponse(), cachedResponse).then(
                        Mono.empty());
                }
                // decorate the ServerHttpResponse to cache the response
                var decoratedExchange = exchange.mutate()
                    .response(new CacheResponseDecorator(exchange, cacheKey))
                    .build();
                return chain.filter(decoratedExchange).then(Mono.empty());
            }))
            .flatMap(securityContext -> chain.filter(exchange).then(Mono.empty()));
    }

    private boolean requestCacheable(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod())
            && !hasRequestBody(request)
            && enableCacheByCacheControl(request.getHeaders());
    }

    private boolean enableCacheByCacheControl(HttpHeaders headers) {
        return headers.getOrEmpty(CACHE_CONTROL)
            .stream()
            .noneMatch(cacheControl ->
                "no-store".equals(cacheControl) || "private".equals(cacheControl));
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
        response.setStatusCode(cachedResponse.statusCode());
        response.getHeaders().clear();
        response.getHeaders().addAll(cachedResponse.headers());
        var body = Flux.fromIterable(cachedResponse.body())
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
                var response = getDelegate();
                body = Flux.from(body)
                    .map(dataBuffer -> {
                        var byteBuffer = allocateDirect(dataBuffer.readableByteCount());
                        dataBuffer.toByteBuffer(byteBuffer);
                        DataBufferUtils.release(dataBuffer);
                        return byteBuffer.asReadOnlyBuffer();
                    })
                    .collectSortedList()
                    .doOnSuccess(byteBuffers -> {
                        var headers = new HttpHeaders();
                        headers.addAll(response.getHeaders());
                        var cachedResponse = new CachedResponse(response.getStatusCode(),
                            headers,
                            byteBuffers,
                            Instant.now());
                        cache.put(cacheKey, cachedResponse);
                    })
                    .flatMapMany(Flux::fromIterable)
                    .map(byteBuffer -> response.bufferFactory().wrap(byteBuffer));
            }
            // write the response
            return super.writeWith(body);
        }
    }
}
