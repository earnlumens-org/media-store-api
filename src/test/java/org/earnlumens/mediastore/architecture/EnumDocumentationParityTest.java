package org.earnlumens.mediastore.architecture;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N10 maintenance guard (MYCELIUM-RESILIENCE.md §8.4): every enum listed in
 * {@code docs/DATA-MODEL.md} §14 must match its {@code .java} counterpart
 * value for value. The table is the "source of truth" a new session reads
 * instead of the code; when it lies the plan builds on sand (H9 was the
 * example). Fails on: a documented enum that no longer exists, a documented
 * value missing from the code, or a code value missing from the document.
 */
class EnumDocumentationParityTest {

    private static final Path DATA_MODEL = Path.of("docs", "DATA-MODEL.md");
    private static final String BASE_PACKAGE = "org.earnlumens.mediastore";
    /** Rows of the §14 table: {@code | `Name` ... | values | used by |}. */
    private static final Pattern ROW = Pattern.compile("^\\|\\s*`([A-Za-z]+)`([^|]*)\\|([^|]*)\\|");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern CONSTANT = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");

    /** Documented enum name → documented constant values (in table order). */
    private static Map<String, Set<String>> documentedEnums() throws IOException {
        List<String> lines = Files.readAllLines(DATA_MODEL, StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## 14.")) { start = i; break; }
        }
        assertTrue(start >= 0, "DATA-MODEL.md §14 (Enums Reference) not found");

        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ")) break;
            Matcher row = ROW.matcher(line);
            if (!row.find()) continue;
            String nameCell = row.group(1) + row.group(2);
            String valuesCell = row.group(3);
            if (nameCell.contains("(admin-api)")) continue; // lives in another repository
            Set<String> values = new LinkedHashSet<>();
            Matcher bt = BACKTICKED.matcher(valuesCell);
            while (bt.find()) {
                Matcher c = CONSTANT.matcher(bt.group(1));
                while (c.find()) values.add(c.group());
            }
            // "`A` / `B`" in the name cell documents two enums with the same values.
            result.put(row.group(1), values);
            Matcher names = BACKTICKED.matcher(row.group(2));
            while (names.find()) {
                String name = names.group(1).trim();
                if (name.matches("[A-Za-z]+")) result.put(name, values);
            }
        }
        assertFalse(result.isEmpty(), "no enum rows parsed from §14");
        return result;
    }

    /** Simple name → enum class, for every enum under the base package. */
    private static Map<String, Class<?>> codeEnums() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                        return true; // include nested enums too
                    }
                };
        scanner.addIncludeFilter(new TypeFilter() {
            @Override
            public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
                return "java.lang.Enum".equals(reader.getClassMetadata().getSuperClassName());
            }
        });
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                Class<?> cls = Class.forName(bd.getBeanClassName());
                if (cls.isEnum()) result.putIfAbsent(cls.getSimpleName(), cls);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    @Test
    void section14_isPresentAndParsable() throws IOException {
        Map<String, Set<String>> documented = documentedEnums();
        assertTrue(documented.containsKey("EntryStatus"));
        assertTrue(documented.containsKey("TargetType"));
        assertTrue(documented.get("EntryStatus").contains("DELETED"));
    }

    @TestFactory
    Stream<DynamicTest> everyDocumentedEnumMatchesItsJavaCounterpart() throws IOException {
        Map<String, Set<String>> documented = documentedEnums();
        Map<String, Class<?>> code = codeEnums();
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : documented.entrySet()) {
            tests.add(DynamicTest.dynamicTest("§14 " + e.getKey() + " == code", () -> {
                Class<?> cls = code.get(e.getKey());
                assertTrue(cls != null, "DATA-MODEL.md §14 documents enum `" + e.getKey()
                        + "` but no such enum exists under " + BASE_PACKAGE);
                Set<String> inCode = Arrays.stream(cls.getEnumConstants())
                        .map(c -> ((Enum<?>) c).name())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                assertEquals(inCode, e.getValue(), "values of `" + e.getKey() + "` diverge: code=" + inCode
                        + " doc=" + e.getValue() + " — update DATA-MODEL.md §14 in the same change");
            }));
        }
        return tests.stream();
    }
}
