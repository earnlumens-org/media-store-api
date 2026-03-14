package org.earnlumens.mediastore.architecture;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Architecture test that enforces tenant isolation at the repository layer.
 *
 * <p>Every method on a <em>tenant-scoped</em> repository interface must either:
 * <ul>
 *   <li>accept a {@code tenantId} parameter (conventionally the first {@code String}), or</li>
 *   <li>be listed in the per-repository cross-tenant allowlist below, or</li>
 *   <li>be a {@code save()} method (the entity already carries its tenantId).</li>
 * </ul>
 *
 * <p>Repositories that are inherently <em>not</em> tenant-scoped (e.g. {@code UserRepository},
 * {@code FounderRepository}, {@code FeedbackRepository}) are excluded entirely.
 *
 * <p>If this test fails after adding a new repository or method, the developer
 * must either add a {@code tenantId} parameter or explicitly register the method
 * in the allowlist with a justification comment.
 */
class TenantIsolationArchTest {

    // ── Repositories that are NOT tenant-scoped (global entities) ───
    private static final Set<String> EXCLUDED_REPOSITORIES = Set.of(
            "org.earnlumens.mediastore.domain.user.repository.UserRepository",
            "org.earnlumens.mediastore.domain.waitlist.repository.FounderRepository",
            "org.earnlumens.mediastore.domain.waitlist.repository.FeedbackRepository"
    );

    // ── Per-repository allowlist for cross-tenant methods ───────────
    // Each entry is "SimpleClassName#methodName". Add here ONLY with a
    // comment explaining why cross-tenant access is safe.
    private static final Set<String> CROSS_TENANT_ALLOWLIST = Set.of(
            // Platform-level dispatching picks PENDING jobs across all tenants
            "TranscodingJobRepository#findAllByStatus",
            // Platform-level watchdog recovers stale jobs across all tenants
            "TranscodingJobRepository#findAllStaleJobs"
    );

    // ── Methods that carry tenantId inside the entity (e.g. save) ──
    private static final Set<String> ENTITY_CARRIER_METHODS = Set.of("save");

    @TestFactory
    Stream<DynamicTest> allTenantScopedRepositoryMethods_requireTenantId() {
        Set<Class<?>> repoInterfaces = findRepositoryInterfaces("org.earnlumens.mediastore.domain");

        List<DynamicTest> tests = new ArrayList<>();

        for (Class<?> repo : repoInterfaces) {
            if (EXCLUDED_REPOSITORIES.contains(repo.getName())) {
                continue;
            }

            String simpleRepoName = repo.getSimpleName();

            for (Method method : repo.getDeclaredMethods()) {
                String methodName = method.getName();
                String qualifiedKey = simpleRepoName + "#" + methodName;

                // Skip save-type methods (entity carries tenantId)
                if (ENTITY_CARRIER_METHODS.contains(methodName)) {
                    continue;
                }

                // Skip explicitly allowlisted cross-tenant methods
                if (CROSS_TENANT_ALLOWLIST.contains(qualifiedKey)) {
                    continue;
                }

                tests.add(DynamicTest.dynamicTest(
                        qualifiedKey + " must accept tenantId",
                        () -> assertHasTenantIdParameter(repo, method)
                ));
            }
        }

        // Safety check: if we found zero repositories something is wrong with classpath scanning
        if (tests.isEmpty()) {
            fail("No tenant-scoped repository methods found — check classpath scanning configuration.");
        }

        return tests.stream();
    }

    private void assertHasTenantIdParameter(Class<?> repo, Method method) {
        Parameter[] params = method.getParameters();
        boolean hasTenantId = false;

        for (Parameter p : params) {
            // Check by parameter name (requires -parameters compiler flag) or by convention
            String name = p.getName();
            if ("tenantId".equals(name)
                    || name.matches("arg\\d+") && p.getType() == String.class
                       && method.getName().contains("TenantId")) {
                hasTenantId = true;
                break;
            }
        }

        // Fallback: check if the method name contains "TenantId" or "ByTenantId"
        if (!hasTenantId) {
            hasTenantId = method.getName().contains("TenantId")
                       || method.getName().contains("tenantId");
        }

        assertTrue(hasTenantId,
                String.format(
                        "TENANT ISOLATION VIOLATION: %s.%s() does not include a tenantId parameter. "
                        + "Every query/mutation on a tenant-scoped repository must be scoped by tenantId. "
                        + "Either add a tenantId parameter, or if this is a legitimate cross-tenant operation, "
                        + "add '%s#%s' to CROSS_TENANT_ALLOWLIST in TenantIsolationArchTest with a justification.",
                        repo.getSimpleName(), method.getName(),
                        repo.getSimpleName(), method.getName()));
    }

    /**
     * Scans the classpath for interfaces ending in "Repository" under the given package.
     */
    private Set<Class<?>> findRepositoryInterfaces(String basePackage) {
        Set<Class<?>> result = new HashSet<>();
        String path = basePackage.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        try {
            Enumeration<URL> resources = cl.getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    scanDirectory(new File(resource.toURI()), basePackage, result);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan classpath for repository interfaces", e);
        }

        return result;
    }

    private void scanDirectory(File dir, String packageName, Set<Class<?>> result) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), result);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                try {
                    Class<?> cls = Class.forName(className);
                    if (cls.isInterface() && cls.getSimpleName().endsWith("Repository")) {
                        result.add(cls);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // Skip classes that can't be loaded
                }
            }
        }
    }
}
