package com.mandateshield.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${mandateshield.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:}}")
    private String customOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                List<String> origins = new ArrayList<>(List.of(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://*.onrender.com",
                        "https://onrender.com",
                        "https://*.vercel.app",
                        "https://vercel.app",
                        "https://*.netlify.app",
                        "https://netlify.app"
                ));
                if (customOrigins != null && !customOrigins.isBlank()) {
                    Arrays.stream(customOrigins.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(origins::add);
                }

                registry.addMapping("/**")
                        .allowedOriginPatterns(origins.toArray(new String[0]))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
                        .allowedHeaders("*")
                        .exposedHeaders("Authorization", "Content-Type")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
