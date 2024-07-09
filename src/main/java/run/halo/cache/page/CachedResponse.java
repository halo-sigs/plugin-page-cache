package run.halo.cache.page;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import lombok.Builder;
import lombok.Data;

/**
 * Cached response. Refer to
 * <a href=
 * "https://github.com/spring-cloud/spring-cloud-gateway/blob/f98aa6d47bf802019f07063f4fd7af6047f15116/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/factory/cache/CachedResponse.java">here</a>
 * }
 */
@Data
@Builder
public class CachedResponse {

    private HttpStatusCode statusCode;

    private HttpHeaders headers;

    private List<ByteBuffer> body;

    private Instant timestamp;

}
