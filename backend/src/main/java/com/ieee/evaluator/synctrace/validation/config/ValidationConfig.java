package com.ieee.evaluator.synctrace.validation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ValidationProperties.class)
public class ValidationConfig {
}
