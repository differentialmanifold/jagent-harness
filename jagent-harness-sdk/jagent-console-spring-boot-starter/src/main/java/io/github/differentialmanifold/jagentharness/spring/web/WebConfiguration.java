package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final ConsoleProperties properties;

    public WebConfiguration(ConsoleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MainbotAccess())
                .addPathPatterns("/api/v1/personal/agents", "/api/v1/personal/agents/**");
    }

    private static final class MainbotAccess implements HandlerInterceptor {
        @Override
        public boolean preHandle(
                HttpServletRequest request, HttpServletResponse response, Object handler) {
            // The identity filter sets ATTRIBUTE for authenticated user-token and browser requests.
            if (request.getAttribute(UserAccessService.ATTRIBUTE) == null
                    || UserAccessService.bearer(request) == null) return true;
            Map<?, ?> variables =
                    (Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (variables == null || !PersonalAgentService.MAINBOT_ID.equals(variables.get("agent")))
                throw new ProtocolException(403, "MAINBOT_ONLY", "Send the request to mainbot");
            return true;
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(properties.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Run-Id");
    }
}
