package com.smarika.heartbeat.check;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.smarika.heartbeat.job.JobMode;
import com.smarika.heartbeat.job.JobResponse;
import com.smarika.heartbeat.job.JobStateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/checks")
public class CheckController {

    private final CheckService checkService;
    private final CheckQueryService queryService;
    private final CheckLifecycleService lifecycleService;
    private final JobStateService jobs;

    public CheckController(CheckService checkService, CheckQueryService queryService,
       CheckLifecycleService lifecycleService, JobStateService jobs) {
        this.checkService = checkService;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.jobs = jobs;
    }

    @PostMapping
    public CreateCheckResponse create(@Valid @RequestBody CreateCheckRequest request) {
        return checkService.createCheck(request);
    }

    @GetMapping("/{id}/job")
    public JobResponse job(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @PostMapping("/{id}/job/mode")
    public JobResponse setJobMode(@PathVariable UUID id, @Valid @RequestBody JobModeRequest request) {
        return jobs.setMode(id, request.mode());
    }

    public record JobModeRequest(@NotNull JobMode mode) {}

    @GetMapping
    public Page<CheckSummaryResponse> list(
            @RequestParam(required = false) CheckStatus status,
            Pageable pageable
    ) {

        return queryService.list(status, pageable);
    }


    @GetMapping("/{id}")
    public CheckDetailResponse get(@PathVariable UUID id) {

        return queryService.get(id);
    }


    @GetMapping("/{id}/history")
    public Page<HistoryResponse> history(@PathVariable UUID id, Pageable pageable) {

        return queryService.history(id, pageable);
    }


    @PostMapping("/{id}/pause")
    public void pause(@PathVariable UUID id) {

        lifecycleService.pause(id);
    }


    @PostMapping("/{id}/resume")
    public void resume(@PathVariable UUID id) {

        lifecycleService.resume(id);
    }
}