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
 * <p>The repository deliberately squashed the old V3..V38 development history into the current
 * V1 baseline while retaining V2 and the post-squash production tail beginning at V39. Flyway is
 * valid with that historical jump; what must remain protected is duplicate versions and gaps in
 * the live tail. Treating the deliberate V2 -> V39 jump as an error made CI red even though a
 * clean PostgreSQL instance migrated successfully.
 */
class FlywayMigrationResolutionTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__([a-z0-9_]+)\\.sql$");
    private static final int SQUASHED_BASELINE_LAST_VERSION = 2;
    private static final int LIVE_TAIL_FIRST_VERSION = 39;

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
    @DisplayName("migration history keeps the known squashed baseline and a gapless live tail")
    void versionsAreContiguous() throws IOException {
        List<Integer> versions = migrationFileNames().stream()
                .map(VERSIONED::matcher)
                .filter(Matcher::matches)
                .map(m -> Integer.parseInt(m.group(1)))
                .sorted()
                .toList();

        assertThat(versions).isNotEmpty();
        assertThat(versions.get(0)).isEqualTo(1);
        assertThat(versions).contains(SQUASHED_BASELINE_LAST_VERSION, LIVE_TAIL_FIRST_VERSION);

        List<Integer> liveTail = versions.stream()
                .filter(v -> v >= LIVE_TAIL_FIRST_VERSION)
                .toList();
        assertThat(liveTail).isNotEmpty();
        assertThat(liveTail.get(0)).isEqualTo(LIVE_TAIL_FIRST_VERSION);

        for (int i = 1; i < liveTail.size(); i++) {
            assertThat(liveTail.get(i))
                    .withFailMessage("Gap in live migration versions between V%d and V%d — a skipped number usually "
                            + "means a script was renamed or lost", liveTail.get(i - 1), liveTail.get(i))
                    .isEqualTo(liveTail.get(i - 1) + 1);
        }

        List<Integer> unexpectedHistoricalVersions = versions.stream()
                .filter(v -> v > SQUASHED_BASELINE_LAST_VERSION && v < LIVE_TAIL_FIRST_VERSION)
                .toList();
        assertThat(unexpectedHistoricalVersions)
                .withFailMessage("V3..V38 were intentionally squashed into V1; do not reintroduce partial historical migrations: %s",
                        unexpectedHistoricalVersions)
                .isEmpty();
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
