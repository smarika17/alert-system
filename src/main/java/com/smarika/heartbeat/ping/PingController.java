package com.smarika.heartbeat.ping;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ping")
public class PingController {

    private final PingService pingService;

    public PingController(
            PingService pingService
    ) {
        this.pingService = pingService;
    }


    @PostMapping("/{token}")
    public PingResponse ping(
            @PathVariable String token
    ) {

        return pingService.processPing(token);
    }
}