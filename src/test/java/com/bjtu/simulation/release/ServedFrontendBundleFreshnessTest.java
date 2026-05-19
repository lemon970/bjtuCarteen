package com.bjtu.simulation.release;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Bug-02 真实根因 + 同类预防:served frontend bundle 必须不旧于 sun/src 源码。
 *
 * Bug-02 表面是 InputPage NumberField step 不匹配 rain_emergency 预设的派生
 * peak multiplier。源码层修复(0.1 → 0.05)其实在第 1 轮就到位,但 Spring Boot
 * 实际服务的产物(src/main/resources/static/frontend/assets/index-*.js)是
 * `npm run build:backend` 的输出,而 `mvn -DskipFrontend=true spring-boot:run`
 * 跳过了构建。结果:源码新 / served bundle 旧,用户浏览器加载的一直是修复前的
 * 旧 bundle,"反复修了仍坏"。
 *
 * 该测试在 mvn test 时把关:任何前端源 mtime 比 served bundle 新,立即失败。
 * 让"忘了 build:backend / 用 -DskipFrontend=true 启动"这一类发布漏洞
 * 在 CI 期暴露,而非用户浏览器里发现。
 */
class ServedFrontendBundleFreshnessTest {

    private static final Path FRONTEND_SOURCE_ROOT = Path.of("sun", "src");
    private static final Path SERVED_BUNDLE_ROOT = Path.of(
            "src", "main", "resources", "static", "frontend");

    /** Filesystem mtime 容差:跨平台/跨 fs 容忍 2 秒,不影响"差几小时几天"的真实漂移。 */
    private static final Duration TOLERANCE = Duration.ofSeconds(2);

    @Test
    void servedFrontendBundleMustNotBeStalerThanFrontendSource() throws IOException {
        if (!Files.isDirectory(SERVED_BUNDLE_ROOT)) {
            fail("Served bundle directory missing: " + SERVED_BUNDLE_ROOT.toAbsolutePath()
                    + ". Run `npm run build:backend` from sun/ to populate it.");
        }
        if (!Files.isDirectory(FRONTEND_SOURCE_ROOT)) {
            fail("Frontend source directory missing: " + FRONTEND_SOURCE_ROOT.toAbsolutePath());
        }

        FileEntry newestSource = newestProductionSource();
        FileEntry newestBundle = newestBundleArtifact();

        if (newestSource == null) {
            return;
        }
        if (newestBundle == null) {
            fail("No served bundle artifact found under " + SERVED_BUNDLE_ROOT.toAbsolutePath()
                    + ". Run `npm run build:backend` from sun/ to populate it.");
        }

        Instant sourceTs = newestSource.mtime.toInstant();
        Instant bundleTs = newestBundle.mtime.toInstant();
        boolean fresh = !bundleTs.plus(TOLERANCE).isBefore(sourceTs);

        assertTrue(fresh, () -> String.format(
                "Served frontend bundle is stale.%n"
                        + "  Newest source: %s (mtime=%s)%n"
                        + "  Newest bundle: %s (mtime=%s)%n"
                        + "  Source-newer-by: %s%n"
                        + "Run `cd sun && npm run build:backend` to rebuild the served bundle.",
                newestSource.path, sourceTs,
                newestBundle.path, bundleTs,
                Duration.between(bundleTs, sourceTs)));
    }

    private static FileEntry newestProductionSource() throws IOException {
        try (Stream<Path> stream = Files.walk(FRONTEND_SOURCE_ROOT)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(ServedFrontendBundleFreshnessTest::isProductionSource)
                    .map(FileEntry::of)
                    .max((a, b) -> a.mtime.compareTo(b.mtime))
                    .orElse(null);
        }
    }

    private static FileEntry newestBundleArtifact() throws IOException {
        try (Stream<Path> stream = Files.walk(SERVED_BUNDLE_ROOT)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".js") || n.endsWith(".css") || n.endsWith(".html");
                    })
                    .map(FileEntry::of)
                    .max((a, b) -> a.mtime.compareTo(b.mtime))
                    .orElse(null);
        }
    }

    /** 排除测试文件、setup 等不进 production bundle 的源,避免误报。 */
    private static boolean isProductionSource(Path path) {
        String name = path.getFileName().toString();
        if (!(name.endsWith(".js") || name.endsWith(".jsx"))) {
            return false;
        }
        if (name.endsWith(".test.js") || name.endsWith(".test.jsx")
                || name.endsWith(".spec.js") || name.endsWith(".spec.jsx")) {
            return false;
        }
        for (Path part : path) {
            String segment = part.toString();
            if (segment.equals("test") || segment.equals("__tests__")) {
                return false;
            }
        }
        return true;
    }

    private static final class FileEntry {
        final Path path;
        final FileTime mtime;

        FileEntry(Path path, FileTime mtime) {
            this.path = path;
            this.mtime = mtime;
        }

        static FileEntry of(Path p) {
            try {
                return new FileEntry(p, Files.getLastModifiedTime(p));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
