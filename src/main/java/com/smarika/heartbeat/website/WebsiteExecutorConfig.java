package com.smarika.heartbeat.website;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WebsiteExecutorConfig {

    @Bean("websiteExecutor")
    public ThreadPoolTaskExecutor websiteExecutor() {

        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(10);

        executor.setMaxPoolSize(30);

        executor.setQueueCapacity(1000);

        executor.setThreadNamePrefix(
                "website-check-"
        );

        executor.initialize();

        return executor;
    }
}