package com.smarika.heartbeat.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AlertExecutorConfig {

    @Bean("alertExecutor")
    public ThreadPoolTaskExecutor alertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(0); // direct hand-off: claimed alert starts immediately or is released
        executor.setThreadNamePrefix("alert-delivery-");
        executor.initialize();
        return executor;
    }
}
