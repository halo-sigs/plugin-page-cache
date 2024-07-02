package run.halo.cache.page;

import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.WebSession;

@Component
public class PrincipalNameResolverImpl implements PrincipalNameResolver {
    private static final String SPRING_SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";
    private static final Expression expression = (new SpelExpressionParser())
        .parseExpression("authentication?.name");

    public String resolveValueFor(WebSession session) {
        Object authentication = session.getAttribute(SPRING_SECURITY_CONTEXT);
        return authentication != null ? expression.getValue(authentication, String.class) : null;
    }
}
