package com.whatsappbot.application.tunnel;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Slf4j
@Service
public class DevTunnelService {

    @Getter
    public enum TunnelStatus {
        IDLE,
        STARTING,
        ACTIVE,
        ERROR
    }

    private volatile String tunnelUrl;
    private volatile TunnelStatus status = TunnelStatus.IDLE;
    private volatile String error;
    private volatile Process process;

    public synchronized TunnelSnapshot snapshot() {
        return new TunnelSnapshot(status.name().toLowerCase(), tunnelUrl, tunnelUrl == null ? null : tunnelUrl + "/webhook", error);
    }

    public synchronized TunnelSnapshot start() {
        if (status == TunnelStatus.ACTIVE && tunnelUrl != null) {
            return snapshot();
        }

        stopInternal();
        status = TunnelStatus.STARTING;
        error = null;
        tunnelUrl = null;

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "ssh",
                    "-o", "StrictHostKeyChecking=no",
                    "-R", "80:localhost:8080",
                    "nokey@localhost.run",
                    "-T"
            );
            builder.redirectErrorStream(true);
            process = builder.start();
            Thread.ofVirtual().name("dev-tunnel-reader").start(this::consumeLogs);
        } catch (IOException e) {
            status = TunnelStatus.ERROR;
            error = e.getMessage();
            log.error("Failed to start localhost.run tunnel", e);
        }

        return snapshot();
    }

    public synchronized TunnelSnapshot stop() {
        stopInternal();
        return snapshot();
    }

    @PreDestroy
    public void shutdown() {
        stopInternal();
    }

    private void consumeLogs() {
        Process activeProcess = process;
        if (activeProcess == null) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(activeProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Tunnel log: {}", line);
                String matchedUrl = extractUrl(line);
                if (matchedUrl != null) {
                    synchronized (this) {
                        tunnelUrl = matchedUrl;
                        status = TunnelStatus.ACTIVE;
                        error = null;
                    }
                } else if (line.toLowerCase().contains("error")) {
                    synchronized (this) {
                        if (tunnelUrl == null) {
                            status = TunnelStatus.ERROR;
                            error = line.length() > 200 ? line.substring(0, 200) : line;
                        }
                    }
                }
            }

            int exitCode = activeProcess.waitFor();
            synchronized (this) {
                if (process == activeProcess) {
                    process = null;
                    if (status != TunnelStatus.ERROR) {
                        status = TunnelStatus.IDLE;
                        tunnelUrl = null;
                    }
                }
            }
            log.info("Tunnel process exited with code {}", exitCode);
        } catch (Exception e) {
            synchronized (this) {
                if (process == activeProcess && tunnelUrl == null) {
                    status = TunnelStatus.ERROR;
                    error = e.getMessage();
                }
            }
            log.error("Tunnel log reader failed", e);
        }
    }

    private String extractUrl(String line) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("https://[a-zA-Z0-9.-]+\\.lhr\\.(life|net|run)")
                .matcher(line);
        return matcher.find() ? matcher.group() : null;
    }

    private synchronized void stopInternal() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        status = TunnelStatus.IDLE;
        tunnelUrl = null;
        error = null;
    }

    public record TunnelSnapshot(String status, String tunnelUrl, String webhookUrl, String error) {}
}
