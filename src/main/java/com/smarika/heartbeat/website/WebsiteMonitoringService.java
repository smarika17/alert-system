package com.smarika.heartbeat.website;

import com.smarika.heartbeat.alert.AlertService;
import com.smarika.heartbeat.check.*;
import com.smarika.heartbeat.history.CheckHistory;
import com.smarika.heartbeat.history.CheckHistoryRepository;
import com.smarika.heartbeat.history.HistoryEventType;
import com.smarika.heartbeat.outage.Outage;
import com.smarika.heartbeat.outage.OutageRepository;
import com.smarika.heartbeat.scheduler.ScheduleCalculator;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class WebsiteMonitoringService {
    private final CheckRepository checks;
    private final ScheduleCalculator schedules;
    private final CheckHistoryRepository history;
    private final OutageRepository outages;
    private final AlertService alerts;

    public WebsiteMonitoringService(CheckRepository checks,
                                    ScheduleCalculator schedules,
                                    CheckHistoryRepository history,
                                    OutageRepository outages,
                                    AlertService alerts) {
        this.checks = checks;
        this.schedules = schedules;
        this.history = history;
        this.outages = outages;
        this.alerts = alerts;
    }

    @Transactional
    public void processResult(UUID checkId, Instant scheduledAt, WebsiteCheckResult result) {
        Check check = checks.findByIdForUpdate(checkId).orElse(null);
        if (check == null || check.getType() != CheckType.WEBSITE
                || check.getStatus() == CheckStatus.PAUSED) {
            return;
        }
        // An old HTTP request must never undo a newer result or a resume.
        // The DB row lock makes this check-and-update atomic.
        if (!scheduledAt.equals(check.getNextExpectedAt())) {
            return;
        }
        Instant now = result.checkedAt();
        // If the service was down for hours, do not replay a huge backlog of polls.
        Instant next = schedules.next(check.getScheduleType(),
                check.getScheduleExpression(), now.isAfter(scheduledAt) ? now : scheduledAt);

        if (result.successful()) {
            CheckStatus old = check.getStatus();
            check.setLastSeenAt(now);
            check.setFailureStartedAt(null);
            check.setStatus(CheckStatus.UP);
            saveHistory(check, HistoryEventType.WEBSITE_SUCCESS, CheckStatus.UP, now,
                    "{\"statusCode\":" + result.statusCode() + ",\"responseTimeMs\":"
                            + result.responseTime().toMillis() + "}");
            if (old != CheckStatus.UP) {
                saveStatusChange(check, old, CheckStatus.UP, now);
            }
            if (old == CheckStatus.DOWN) {
                Outage outage = outages.findFirstByCheckIdAndEndedAtIsNull(check.getId()).orElse(null);
                if (outage != null) {
                    outage.setEndedAt(now);
                    outages.save(outage);
                    alerts.createRecoveryAlert(check, outage, now);
                }
            }
        } else {
            Instant firstFailure = check.getFailureStartedAt();
            if (firstFailure == null) {
                firstFailure = scheduledAt;
                check.setFailureStartedAt(firstFailure);
            }
            Instant graceDeadline = firstFailure.plusSeconds(check.getGracePeriodSeconds());
            boolean graceExpired = !now.isBefore(graceDeadline);
            CheckStatus newStatus = graceExpired ? CheckStatus.DOWN : CheckStatus.LATE;
            saveHistory(check, HistoryEventType.WEBSITE_FAILURE, newStatus, now,
                    "{\"statusCode\":" + result.statusCode() + ",\"error\":\""
                            + escapeJson(result.error()) + "\"}");

            if (check.getStatus() != newStatus) {
                CheckStatus old = check.getStatus();
                check.setStatus(newStatus);
                saveStatusChange(check, old, newStatus, now);
                if (newStatus == CheckStatus.DOWN) {
                    Outage outage = outages.findFirstByCheckIdAndEndedAtIsNull(check.getId()).orElse(null);
                    if (outage == null) {
                        outage = new Outage();
                        outage.setId(UUID.randomUUID());
                        outage.setCheck(check);
                        outage.setStartedAt(graceDeadline);
                        outage.setCreatedAt(now);
                        outage = outages.save(outage);
                        alerts.createDownAlert(check, outage, now);
                    }
                }
            }
            // Probe again when grace expires, even if the configured interval is longer.
            if (!graceExpired && graceDeadline.isBefore(next)) {
                next = graceDeadline;
            }
        }
        check.setNextExpectedAt(next);
        check.setNextActionAt(next);
        check.setUpdatedAt(now);
        checks.save(check);
    }

    private void saveStatusChange(Check check, CheckStatus from, CheckStatus to, Instant now) {
        saveHistory(check, HistoryEventType.STATUS_CHANGED, to, now,
                "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
    }

    private void saveHistory(Check check, HistoryEventType eventType, CheckStatus status,
                             Instant when, String details) {
        CheckHistory event = new CheckHistory();
        event.setCheck(check);
        event.setEventType(eventType);
        event.setStatus(status);
        event.setOccurredAt(when);
        event.setDetails(details);
        history.save(event);
    }

    private String escapeJson(String message) {
        if (message == null) return "";
        return message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
