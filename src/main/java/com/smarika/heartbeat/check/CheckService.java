package com.smarika.heartbeat.check;

import com.smarika.heartbeat.common.TokenService;
import com.smarika.heartbeat.job.JobStateService;
import com.smarika.heartbeat.scheduler.ScheduleCalculator;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.UUID;

@Service
public class CheckService {

    private final CheckRepository checkRepository;
    private final ScheduleCalculator scheduleCalculator;
    private final TokenService tokenService;
    private final String publicBaseUrl;
    private final JobStateService jobs;

    public CheckService(
            CheckRepository checkRepository,
            ScheduleCalculator scheduleCalculator,
            TokenService tokenService,
            JobStateService jobs,
            @Value("${monitoring.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.checkRepository = checkRepository;
        this.scheduleCalculator = scheduleCalculator;
        this.tokenService = tokenService;
        this.jobs = jobs;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public CreateCheckResponse createCheck( CreateCheckRequest request) {

        validateRequest(request);

        Instant now = Instant.now();

        Check check = new Check();

        check.setId(UUID.randomUUID());
        check.setName(request.name());
        check.setType(request.type());
        check.setStatus(CheckStatus.UP);
        check.setScheduleType(request.scheduleType());
        check.setScheduleExpression(
            request.scheduleExpression()
        );
        check.setGracePeriodSeconds(
            request.gracePeriodSeconds()
        );
        check.setRunAutomatically(request.runAutomatically());

        check.setAlertUrl(request.alertUrl() == null || request.alertUrl().isBlank()
                ? publicBaseUrl + "/api/alerts/receive" : request.alertUrl());
        check.setCreatedAt(now);
        check.setUpdatedAt(now);

        check.setNextExpectedAt(
            scheduleCalculator.next(
                request.scheduleType(),
                request.scheduleExpression(),
                now
            )
        );

        check.setNextActionAt(check.getNextExpectedAt());

        String rawToken = null;

        if (request.type() == CheckType.HEARTBEAT) {
            rawToken = tokenService.generateToken();
            check.setPingTokenHash(
                tokenService.hashToken(rawToken)
            );
            check.setTargetUrl(null);
        } else {
            check.setTargetUrl(
                request.targetUrl()
            );
            check.setPingTokenHash(null);
        }

        checkRepository.save(check);

        String pingUrl = null;

        if (rawToken != null) {
            pingUrl = publicBaseUrl + "/api/ping/" + rawToken;
        }

        if (request.runAutomatically()) {
            jobs.register(check, pingUrl);
        }

        return new CreateCheckResponse(
            check.getId(),
            check.getName(),
            check.getType(),
            check.getStatus(),
            check.getScheduleType(),
            check.getScheduleExpression(),
            check.getGracePeriodSeconds(),
            check.getTargetUrl(),
            pingUrl,
            check.getAlertUrl(),
            check.getNextExpectedAt(),
            check.isRunAutomatically()
        );
    }

    private void validateRequest(CreateCheckRequest request) {
        if (request.runAutomatically() && request.type() != CheckType.HEARTBEAT) {
            throw new IllegalArgumentException("Only heartbeat checks can run an internal job");
        }
        if (
            request.type() == CheckType.HEARTBEAT &&
            request.targetUrl() != null &&
            !request.targetUrl().isBlank()) {

            throw new IllegalArgumentException(
                "Heartbeat check cannot have targetUrl"
            );
        }

        if (request.type() == CheckType.WEBSITE &&
            (request.targetUrl() == null ||
            request.targetUrl().isBlank()
            )) {
            throw new IllegalArgumentException(
                "Website check requires targetUrl"
            );
        }
    }
}
