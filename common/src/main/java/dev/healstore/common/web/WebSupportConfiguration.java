package dev.healstore.common.web;

import dev.healstore.common.core.PeerIdentityPolicy;
import dev.healstore.common.core.UnfinishedPeerIdentityPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.cert.X509Certificate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Import(ApiExceptionHandler.class)
public class WebSupportConfiguration {
    @Bean
    PeerIdentityPolicy peerIdentityPolicy() { return new UnfinishedPeerIdentityPolicy(); }

    @Bean
    WebMvcConfigurer identityBoundary(PeerIdentityPolicy identity) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        var chain = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
                        identity.authorize(chain, request.getMethod(), request.getRequestURI());
                        return true;
                    }
                }).addPathPatterns("/api/**").excludePathPatterns("/api/v1/capabilities");
            }
        };
    }
}
