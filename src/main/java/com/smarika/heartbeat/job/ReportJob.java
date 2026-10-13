package com.smarika.heartbeat.job;

import com.smarika.heartbeat.check.CheckRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

@Component
public class ReportJob {
    private final CheckRepository checks;
    private final Path directory;

    public ReportJob(CheckRepository checks,
                     @Value("${monitoring.job-report-directory:./job-reports}") String directory) {
        this.checks = checks;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public Path execute(JobStateService.Execution job) throws IOException {
        if (job.mode() == JobMode.FAILURE) {
            throw new IOException("Report generation failed: failure mode enabled");
        }
        // Real Java work: read the database and write a report before reporting success.
        String report = "Job: " + job.name() + "\nCheck ID: " + job.checkId()
                + "\nGenerated at: " + Instant.now() + "\nRegistered checks: " + checks.count() + "\n";
        Files.createDirectories(directory);
        Path destination = directory.resolve(job.checkId() + ".txt");
        Path temporary = Files.createTempFile(directory, job.checkId() + "-", ".tmp");
        try {
            Files.writeString(temporary, report, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
