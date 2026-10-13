package com.smarika.heartbeat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HeartbeatMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                HeartbeatMonitorApplication.class,
                args
        );
    }
}