package run.halo.cache.page;

import org.springframework.web.server.WebSession;

public interface PrincipalNameResolver {
    String resolveValueFor(WebSession session);
}
