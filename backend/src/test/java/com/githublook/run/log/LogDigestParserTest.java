package com.githublook.run.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogDigestParser · 日志摘要")
class LogDigestParserTest {

    private static final String TS = "2026-11-20T06:21:%02d.9021884Z ";

    /** 给每行加上 GitHub 风格的 ISO 时间戳前缀 */
    private static String withTimestamps(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format(TS, i % 60)).append(lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("空输入与成功日志")
    class Basic {

        @Test
        @DisplayName("null / 空串 / 纯空白都返回空摘要，不抛异常")
        void emptyInput() {
            for (String input : new String[]{null, "", "   \n  \n"}) {
                LogDigest digest = LogDigestParser.parse(input);
                assertThat(digest.verdict()).isEqualTo("unknown");
                assertThat(digest.failureClass()).isEqualTo("none");
                assertThat(digest.highlights()).isEmpty();
            }
        }

        @Test
        @DisplayName("全是正常输出时判为 success，并把所有行算作可折叠")
        void successLog() {
            String raw = withTimestamps(
                    "##[group]Run pytest",
                    "collected 42 items",
                    "42 passed in 3.21s",
                    "##[endgroup]");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.verdict()).isEqualTo("success");
            assertThat(digest.failureClass()).isEqualTo("none");
            assertThat(digest.headline()).isEqualTo("未发现错误输出");
            assertThat(digest.highlights()).isEmpty();
            assertThat(digest.stats().totalLines()).isEqualTo(4);
            assertThat(digest.stats().collapsedLines()).isEqualTo(4);
        }

        @Test
        @DisplayName("##[group] / ##[endgroup] 这类结构行不会被当成错误")
        void structuralLinesIgnored() {
            String raw = withTimestamps("##[group]Run actions/checkout@v4", "##[endgroup]");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.verdict()).isEqualTo("success");
            assertThat(digest.stats().errorLines()).isZero();
        }
    }

    @Nested
    @DisplayName("测试失败识别")
    class TestFailures {

        @Test
        @DisplayName("pytest 失败：定位到类别、行号和文件")
        void pytestFailure() {
            String raw = withTimestamps(
                    "##[group]Run pytest",
                    "FAILED tests/test_order.py::test_create - AssertionError",
                    "E   assert 400 == 200",
                    "##[error]Process completed with exit code 1");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.verdict()).isEqualTo("failure");
            assertThat(digest.failureClass()).isEqualTo("test");
            assertThat(digest.headline()).isEqualTo("1 处测试失败，集中在 tests/test_order.py");

            LogDigest.Highlight first = digest.highlights().get(0);
            // 第 2 行是 FAILED 那行（1-based）
            assertThat(first.lineNo()).isEqualTo(2);
            assertThat(first.category()).isEqualTo("test");
            assertThat(first.title()).isEqualTo("测试失败");
            assertThat(first.detail()).contains("test_create");
            // 断言值来自上方的 traceback，被拼到了主错误后面
            assertThat(first.detail()).contains("assert 400 == 200");
        }

        @Test
        @DisplayName("两个相邻的 FAILED 各算一处失败")
        void twoAdjacentFailures() {
            String raw = withTimestamps(
                    "FAILED tests/test_x.py::test_a - AssertionError: 1 != 2",
                    "FAILED tests/test_x.py::test_b - AssertionError: 3 != 4");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.stats().blockCount()).isEqualTo(2);
            assertThat(digest.headline()).isEqualTo("2 处测试失败，集中在 tests/test_x.py");
        }

        @Test
        @DisplayName("紧跟在失败行后面的断言/堆栈不会额外算一处")
        void detailLinesDoNotAddBlocks() {
            String raw = withTimestamps(
                    "FAILED tests/test_x.py::test_a - AssertionError",
                    "E   assert 400 == 200",
                    "E    +  where 400 = resp.status");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.stats().blockCount()).isEqualTo(1);
            assertThat(digest.headline()).isEqualTo("1 处测试失败，集中在 tests/test_x.py");
        }

        @Test
        @DisplayName("Maven / JUnit 的 Tests run 汇总行也能识别")
        void junitSummary() {
            String raw = withTimestamps(
                    "[INFO] Running com.example.OrderTest",
                    "Tests run: 5, Failures: 2, Errors: 0, Skipped: 0");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("test");
            assertThat(digest.verdict()).isEqualTo("failure");
        }

        @Test
        @DisplayName("Jest 的 ✕ 标记识别为测试失败")
        void jestFailure() {
            String raw = withTimestamps(
                    "FAIL src/utils/format.spec.ts",
                    "  ✕ formats duration (12 ms)");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("test");
            assertThat(digest.headline()).contains("src/utils/format.spec.ts");
        }
    }

    @Nested
    @DisplayName("其他失败类型")
    class OtherFailures {

        @Test
        @DisplayName("npm 报错归为依赖问题")
        void npmError() {
            String raw = withTimestamps(
                    "##[group]Run npm ci",
                    "npm error code E404",
                    "npm error 404 Not Found - GET https://registry.npmjs.org/left-pad");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("dependency");
            assertThat(digest.headline()).isEqualTo("依赖解析失败");
        }

        @Test
        @DisplayName("Maven 目标执行失败归为构建问题")
        void mavenBuildFailure() {
            String raw = withTimestamps(
                    "[INFO] BUILD FAILURE",
                    "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("build");
            assertThat(digest.headline()).isEqualTo("构建失败");
        }

        @Test
        @DisplayName("超时被单独归类")
        void timeout() {
            String raw = withTimestamps(
                    "The job running on runner GitHub Actions 3 has exceeded the maximum execution time of 360 minutes.");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("timeout");
            assertThat(digest.headline()).isEqualTo("执行超时被终止");
        }

        @Test
        @DisplayName("##[error] 包裹的权限错误不会被当成 generic error")
        void permissionFromErrorMarker() {
            String raw = withTimestamps(
                    "##[error]Resource not accessible by integration");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("permission");
            // ##[error] 前缀被剥掉，只留正文
            assertThat(digest.highlights().get(0).detail())
                    .isEqualTo("Resource not accessible by integration");
        }
    }

    @Nested
    @DisplayName("去重与上限")
    class Dedup {

        @Test
        @DisplayName("完全相同的错误合并为一条，并累计出现次数")
        void samePatternMerged() {
            String raw = withTimestamps(
                    "npm error 404 Not Found - GET https://registry.npmjs.org/left-pad",
                    "npm error 404 Not Found - GET https://registry.npmjs.org/left-pad",
                    "npm error 404 Not Found - GET https://registry.npmjs.org/left-pad");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.highlights()).hasSize(1);
            assertThat(digest.highlights().get(0).occurrences()).isEqualTo(3);
        }

        @Test
        @DisplayName("不同错误最多展示 5 条，但统计里保留真实总数")
        void cappedAtFive() {
            String[] words = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta"};
            String[] lines = IntStream.range(0, words.length)
                    .mapToObj(i -> "##[error]" + words[i] + " blew up")
                    .toArray(String[]::new);

            LogDigest digest = LogDigestParser.parse(withTimestamps(lines));

            assertThat(digest.highlights()).hasSize(5);
            assertThat(digest.stats().blockCount()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("上下文与超长日志")
    class ContextAndWindow {

        @Test
        @DisplayName("上下文包含错误行前后若干行，且能指回原文")
        void contextWindow() {
            String[] lines = new String[30];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = "noise line " + i;
            }
            lines[15] = "##[error]something exploded";

            LogDigest digest = LogDigestParser.parse(withTimestamps(lines));

            LogDigest.Highlight highlight = digest.highlights().get(0);
            assertThat(highlight.lineNo()).isEqualTo(16);
            assertThat(highlight.context()).hasSize(16); // 前 3 + 本体 + 后 12
            assertThat(highlight.context()).anyMatch(line -> line.contains("something exploded"));
            assertThat(digest.stats().collapsedLines()).isGreaterThan(0);
        }

        @Test
        @DisplayName("3000 行的日志里，末尾的错误仍然能被抓到")
        void findsErrorInTail() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3000; i++) {
                sb.append(String.format(TS, i % 60))
                        .append(i == 2900 ? "##[error]late failure" : "compile output line " + i)
                        .append('\n');
            }

            LogDigest digest = LogDigestParser.parse(sb.toString());

            assertThat(digest.verdict()).isEqualTo("failure");
            assertThat(digest.stats().totalLines()).isEqualTo(3001);
            assertThat(digest.highlights().get(0).lineNo()).isEqualTo(2901);
        }
    }


    @Nested
    @DisplayName("证据等级：测试输出里的假错误不能当结论")
    class EvidenceRanking {

        /**
         * 现场复现（2026-11-24，CI #4）：Maven 输出里既有测试打印的 401/403，
         * 也有逐类汇总、具体失败方法、run 级总计。摘要应当指出「哪个测试挂了、为什么」。
         */
        private static final String REAL_LOG = """
                [INFO] Running com.githublook.run.RunWriteServiceTest$CommitEnrichment
                08:43:12.340 [main] WARN com.githublook.github.GithubApiClient -- github_api_failed action=rerun repo=acme/app run=1 status=401 detail=Bad credentials
                08:43:12.489 [main] WARN com.githublook.github.GithubApiClient -- github_api_failed action=rerun repo=acme/app run=1 status=403 detail=Resource not accessible by integration
                [ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.006 s <<< FAILURE! -- in com.githublook.run.RunWriteServiceTest$CommitEnrichment
                [ERROR] com.githublook.run.RunWriteServiceTest.updatesExistingRun -- Time elapsed: 0.013 s <<< FAILURE!
                Wanted but not invoked:
                runMapper.updateById(
                [ERROR] Tests run: 302, Failures: 3, Errors: 0, Skipped: 0
                """;

        @Test
        @DisplayName("WARN 401/403 是测试输出，不能盖过测试框架的汇总结论")
        void testOutputDoesNotOverrideSummary() {
            LogDigest digest = LogDigestParser.parse(withTimestamps(REAL_LOG.strip().split("\n")));

            assertThat(digest.failureClass()).isEqualTo("test");
            assertThat(digest.highlights()).isNotEmpty();
            assertThat(digest.highlights()).allSatisfy(h -> assertThat(h.category()).isEqualTo("test"));
        }

        @Test
        @DisplayName("数量取 run 级总计，不把逐类汇总重复相加")
        void countUsesRunLevelTotal() {
            LogDigest digest = LogDigestParser.parse(withTimestamps(REAL_LOG.strip().split("\n")));

            // 逐类是 1，总计是 3；相加会得到 4，取总计才是对的
            assertThat(digest.headline()).isEqualTo("3 处测试失败");
        }

        @Test
        @DisplayName("摘要指出具体失败的测试方法与原因，而不是记账行")
        void pointsAtConcreteFailure() {
            LogDigest digest = LogDigestParser.parse(withTimestamps(REAL_LOG.strip().split("\n")));

            LogDigest.Highlight first = digest.highlights().get(0);
            assertThat(first.detail()).contains("RunWriteServiceTest.updatesExistingRun");
            assertThat(first.detail()).contains("Wanted but not invoked");
            // 不应该把 "Tests run: 302" 这种汇总当摘要内容
            assertThat(digest.highlights())
                    .noneSatisfy(h -> assertThat(h.detail()).contains("Tests run: 302"));
        }

        @Test
        @DisplayName("没有测试汇总时，WARN 中的权限错误才会被当作真错误")
        void warnPermissionKeptWhenAlone() {
            String raw = withTimestamps(
                    "08:43:12.340 [main] WARN com.x.Y -- api call failed status=401 detail=Bad credentials");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("permission");
        }


        @Test
        @DisplayName("setup-node 找不到锁文件 → 归为依赖问题，而不是 unknown")
        void setupNodeCacheFailureIsDependency() {
            // 现场（CI #2，main@4b2b00b）：job 在装 Node 阶段就挂了，测试一行没跑
            LogDigest digest = LogDigestParser.parse(withTimestamps(
                    "##[group]Run actions/setup-node@v4",
                    "Found in cache @ /opt/hostedtoolcache/node/22.23.5/x64",
                    "##[error]Some specified paths were not resolved, unable to cache dependencies.",
                    "Post job cleanup."));

            assertThat(digest.failureClass()).isEqualTo("dependency");
            assertThat(digest.headline()).contains("依赖");
            // 正文里不该只有那句天书，也要带上原始行供核对
            assertThat(digest.highlights()).isNotEmpty();
        }

        @Test
        @DisplayName("npm ci 与 lockfile 版本不一致 → 依赖问题")
        void npmCiOutOfSyncIsDependency() {
            LogDigest digest = LogDigestParser.parse(withTimestamps(
                    "npm ci can only install packages when your package.json and package-lock.json "
                            + "or npm-shrinkwrap.json are in sync. Please update your lock file with `npm install`."));

            assertThat(digest.failureClass()).isEqualTo("dependency");
        }

        @Test
        @DisplayName("认不出来的日志不再拿某一行冒充结论")
        void unknownDoesNotPromoteNoise() {
            LogDigest digest = LogDigestParser.parse(withTimestamps(
                    "##[error]Something went sideways with code 9"));

            assertThat(digest.failureClass()).isEqualTo("unknown");
            assertThat(digest.headline()).isEqualTo("日志中出现错误输出，未能归类");
        }

        @Test
        @DisplayName("存在更强的构建错误时，WARN 权限行降级为上下文")
        void warnPermissionDemotedWhenBuildFails() {
            String raw = withTimestamps(
                    "08:43:12.340 [main] WARN com.x.Y -- api call failed status=401 detail=Bad credentials",
                    "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("build");
            assertThat(digest.highlights()).allSatisfy(h -> assertThat(h.category()).isEqualTo("build"));
        }

        @Test
        @DisplayName("最终类别相同的错误排在前面，噪声不占据第一眼")
        void sameClassFirst() {
            String raw = withTimestamps(
                    "npm error code E404",
                    "FAILED tests/test_a.py::test_x - AssertionError",
                    "FAILED tests/test_b.py::test_y - AssertionError");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("test");
            assertThat(digest.highlights().get(0).category()).isEqualTo("test");
        }

        @Test
        @DisplayName("pytest 的「2 failed, 40 passed」也作为权威数量")
        void pytestSummaryCounts() {
            String raw = withTimestamps(
                    "FAILED tests/test_a.py::test_x - AssertionError",
                    "FAILED tests/test_b.py::test_y - AssertionError",
                    "2 failed, 40 passed in 5.42s");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("test");
            assertThat(digest.headline()).startsWith("2 处测试失败");
        }
    }

    @Nested
    @DisplayName("不瞎猜")
    class Honesty {

        @Test
        @DisplayName("认不出的错误只给原文，不编造具体类别")
        void unknownStaysUnknown() {
            String raw = withTimestamps("##[error]Something went sideways with code 9");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.verdict()).isEqualTo("failure");
            assertThat(digest.failureClass()).isEqualTo("unknown");
            assertThat(digest.highlights().get(0).detail()).isEqualTo("Something went sideways with code 9");
        }

        @Test
        @DisplayName("每条摘要都带可回溯的行号")
        void everyHighlightHasLineNumber() {
            String raw = withTimestamps(
                    "noise",
                    "FAILED tests/a.py::test_x - AssertionError",
                    "noise",
                    "##[error]Process completed with exit code 1");

            List<LogDigest.Highlight> highlights = LogDigestParser.parse(raw).highlights();

            // 有具体错误时，exit code 这类收尾行不占摘要位
            assertThat(highlights).hasSize(1);
            assertThat(highlights.get(0).lineNo()).isEqualTo(2);
        }

        @Test
        @DisplayName("只有 exit code 收尾行时，仍然展示出来")
        void genericErrorShownWhenAlone() {
            String raw = withTimestamps("##[error]Process completed with exit code 2");

            LogDigest digest = LogDigestParser.parse(raw);

            assertThat(digest.failureClass()).isEqualTo("unknown");
            assertThat(digest.highlights()).hasSize(1);
            assertThat(digest.highlights().get(0).detail()).isEqualTo("Process completed with exit code 2");
        }
    }
}
