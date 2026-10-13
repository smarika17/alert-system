package com.smarika.heartbeat.scheduler;

import com.smarika.heartbeat.check.ScheduleType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Component
public class ScheduleCalculator {

    public Instant next(
        ScheduleType scheduleType,
        String expression,
        Instant startTime
    ) {
        if (scheduleType == ScheduleType.INTERVAL) {
            long seconds;
            try {
                seconds = Long.parseLong(expression);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Interval must be a number of seconds"
                );
            }

            if (seconds <= 0) {
                throw new IllegalArgumentException(
                    "Interval must be greater than zero"
                );
            }

            return startTime.plusSeconds(seconds);
        }

        CronExpression cron = CronExpression.parse(expression);

        ZonedDateTime base =
                startTime.atZone(ZoneOffset.UTC);

        ZonedDateTime next =
                cron.next(base);

        if (next == null) {
            throw new IllegalArgumentException(
                    "Schedule has no next occurrence"
            );
        }

        return next.toInstant();
    }
}