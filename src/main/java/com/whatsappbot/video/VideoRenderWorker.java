package com.whatsappbot.video;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoRenderWorker {

    private final VideoRenderJobStateService stateService;
    private final BlenderRenderProperties properties;

    public void render(UUID jobId, UUID tenantId) {
        VideoRenderJobEntity job = stateService.markRendering(tenantId, jobId);
        if (job == null) {
            return;
        }

        Path jobDir = properties.workDir().toAbsolutePath().normalize().resolve(jobId.toString());
        Path planFile = jobDir.resolve("scene-plan.json");
        Path outputFile = jobDir.resolve("video.mp4");
        Path logFile = jobDir.resolve("blender.log");

        try {
            Files.createDirectories(jobDir);
            Files.writeString(planFile, job.getScenePlan(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            List<String> command = new ArrayList<>();
            command.add(properties.executable());
            command.add("-b");
            Path templateRoot = properties.templateDir().toAbsolutePath().normalize();
            Path templateFile = templateRoot
                    .resolve(job.getTemplateCode() + ".blend")
                    .normalize();
            if (templateFile.startsWith(templateRoot) && Files.isRegularFile(templateFile)) {
                command.add(templateFile.toString());
            }
            command.add("--python");
            command.add(properties.pythonScript().toAbsolutePath().normalize().toString());
            command.add("--");
            command.add(planFile.toString());
            command.add(outputFile.toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

            boolean finished = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Blender render exceeded " + properties.timeoutSeconds() + " seconds");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputFile)) {
                throw new IllegalStateException("Blender exited with code " + process.exitValue());
            }

            stateService.markCompleted(tenantId, jobId, outputFile.toString(), logFile.toString());
        } catch (Exception e) {
            log.error("Blender render {} failed", jobId, e);
            stateService.markFailed(
                    tenantId,
                    jobId,
                    Files.exists(logFile) ? logFile.toString() : null,
                    shortMessage(e)
            );
        }
    }

    private String shortMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
