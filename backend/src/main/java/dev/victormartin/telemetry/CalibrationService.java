package dev.victormartin.telemetry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Triggers calibration pipeline execution as a background job.
 * Runs the telemetry module's CalibrationPipeline via Gradle.
 */
@Service
public class CalibrationService {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "calibration-runner");
        t.setDaemon(true);
        return t;
    });

    @Value("${calibration.project-dir:..}")
    private String projectDir;

    public CalibrationService(RaceWebSocketHandler raceWebSocketHandler) {
        this.raceWebSocketHandler = raceWebSocketHandler;
    }

    /**
     * Returns true if calibration is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Trigger calibration for a track. Returns true if started, false if already running.
     */
    public boolean triggerCalibration(int trackId) {
        if (!running.compareAndSet(false, true)) {
            System.out.println("CalibrationService: already running, skipping trigger for track " + trackId);
            return false;
        }

        executor.execute(() -> {
            try {
                System.out.println("CalibrationService: starting calibration for track " + trackId);
                long start = System.currentTimeMillis();

                Path projectPath = Path.of(projectDir).toAbsolutePath().normalize();
                ProcessBuilder pb = new ProcessBuilder(
                        "python", "-m", "calibration", "run", String.valueOf(trackId));
                pb.directory(projectPath.toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();

                // Log output
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("  [calibration] " + line);
                    }
                }

                int exitCode = process.waitFor();
                long elapsed = System.currentTimeMillis() - start;

                if (exitCode == 0) {
                    System.out.println("CalibrationService: completed for track " + trackId + " in " + elapsed + "ms");

                    // Notify portal
                    String json = objectMapper.writeValueAsString(Map.of(
                            "type", "calibrationComplete",
                            "trackId", trackId,
                            "elapsedMs", elapsed));
                    raceWebSocketHandler.broadcast(json);
                } else {
                    System.err.println("CalibrationService: failed for track " + trackId + " (exit code " + exitCode + ")");

                    String json = objectMapper.writeValueAsString(Map.of(
                            "type", "calibrationFailed",
                            "trackId", trackId,
                            "exitCode", exitCode));
                    raceWebSocketHandler.broadcast(json);
                }
            } catch (Exception e) {
                System.err.println("CalibrationService: error running calibration: " + e.getMessage());
            } finally {
                running.set(false);
            }
        });

        return true;
    }
}
