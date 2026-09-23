package com.shop.framework.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final InternalTokenInterceptor internalTokenInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        InternalTokenInterceptor internalTokenInterceptor) {
        this.authInterceptor = authInterceptor;
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 服务间令牌拦截器最优先：/inner/** 必须携带正确的 X-Internal-Token
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/inner/**")
                .order(0);
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .order(1)
                .excludePathPatterns(
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/error",
                        "/favicon.ico"
                );
    }
}
