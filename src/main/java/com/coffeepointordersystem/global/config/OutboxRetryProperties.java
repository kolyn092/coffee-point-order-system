package com.coffeepointordersystem.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.retry")
public record OutboxRetryProperties(long publishTimeoutMillis) {

}
