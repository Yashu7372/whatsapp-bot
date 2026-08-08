package com.whatsappbot.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the migration set itself.
 *
 * <p>Two scripts once shipped claiming version 32. Flyway rejects that during resolution — before
 * it opens a connection — so the whole application failed to start on every environment, while the
 * build stayed green because nothing in the test suite touched Flyway. This test needs no database
 * and takes milliseconds, so that class of mistake can never reach a branch again.
 */
class FlywayMigrationResolutionTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__([a-z0-9_]+)\\.sql$");

    @Test
    @DisplayName("no two migrations claim the same version")
    void versionsAreUnique() throws IOException {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (String name : migrationFileNames()) {
            Matcher matcher = VERSIONED.matcher(name);
            assertThat(matcher.matches())
                    .withFailMessage("Migration %s does not follow V<version>__snake_case_description.sql", name)
                    .isTrue();
            byVersion.computeIfAbsent(matcher.group(1), v -> new java.util.ArrayList<>()).add(name);
        }

        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        byVersion.forEach((version, files) -> {
            if (files.size() > 1) duplicates.put(version, files);
        });

        assertThat(duplicates)
                .withFailMessage("Flyway refuses to start when a version is claimed twice. Duplicates: %s", duplicates)
                .isEmpty();
    }

    @Test
    @DisplayName("versions form a gapless sequence starting at 1")
    void versionsAreContiguous() throws IOException {
        List<Integer> versions = migrationFileNames().stream()
                .map(VERSIONED::matcher)
                .filter(Matcher::matches)
                .map(m -> Integer.parseInt(m.group(1)))
                .sorted()
                .toList();

        assertThat(versions).isNotEmpty();
        assertThat(versions.get(0)).isEqualTo(1);
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .withFailMessage("Gap in migration versions between V%d and V%d — a skipped number usually "
                            + "means a script was renamed or lost", versions.get(i - 1), versions.get(i))
                    .isEqualTo(versions.get(i - 1) + 1);
        }
    }

    private static List<String> migrationFileNames() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }
}
