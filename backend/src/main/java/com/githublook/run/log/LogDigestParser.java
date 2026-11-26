package com.githublook.run.log;

import com.githublook.run.log.LogDigest.Highlight;
import com.githublook.run.log.LogDigest.Stats;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 GitHub Actions 的原始日志压成一段可读结论。
 *
 * <p>核心是<b>三级证据模型</b>——不同来源的行可信度并不对等：
 * <table>
 *   <tr><th>等级</th><th>例子</th><th>作用</th></tr>
 *   <tr><td>authoritative</td><td>{@code Tests run: 13, Failures: 1}、{@code 2 failed, 40 passed}</td>
 *       <td>决定 failureClass 与 headline 的数量</td></tr>
 *   <tr><td>primary</td><td>{@code FAILED tests/x.py}、{@code npm error}、{@code [ERROR] Failed to execute goal}</td>
 *       <td>决定 highlights</td></tr>
 *   <tr><td>detail</td><td>{@code E assert 400 == 200}、堆栈、{@code WARN ... status=401}</td>
 *       <td>只作上下文，不单独进入摘要</td></tr>
 * </table>
 *
 * <p>之所以要分等级：单元测试会主动打印「预期内的失败日志」（例如验证鉴权分支时输出
 * {@code WARN ... status=401 Bad credentials}），这些行长得和真错误一模一样。
 * 如果按出现次数投票，噪声会压过测试框架自己给出的权威结论。
 *
 * <p>其余取舍：
 * <ul>
 *   <li>识别不出来就只给「通用错误行 + 上下文」，<b>绝不生成猜测性结论</b></li>
 *   <li>行号始终指向原始日志（1-based），保证能从摘要跳回全文</li>
 *   <li>超长日志只取「前 500 + 后 1500」行做分析——错误基本都在末尾</li>
 * </ul>
 */
public final class LogDigestParser {

    private static final int MAX_HIGHLIGHTS = 5;
    private static final int CONTEXT_BEFORE = 3;
    private static final int CONTEXT_AFTER = 12;
    private static final int HEAD_LINES = 500;
    private static final int TAIL_LINES = 1500;
    private static final int MAX_DETAIL = 220;
    /** 从属细节行（如断言、堆栈）距离主错误行多少行内仍算同一个块 */
    private static final int DETAIL_ATTACH_GAP = 3;

    /** GitHub 日志的行首形如 {@code 2026-11-20T06:21:14.9021884Z } */
    private static final Pattern TIMESTAMP =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z ?");

    /** 应用日志级别标记：{@code 08:43:12.340 [main] WARN com.x.Y -- ...} */
    private static final Pattern LOG_LEVEL = Pattern.compile("\\[?\\b(WARN|INFO|DEBUG)\\b\\]?");

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern BLANK = Pattern.compile("\\s+");
    private static final Pattern PY_FILE = Pattern.compile("(?:FAILED|ERROR)\\s+([\\w./\\-]+\\.py)");
    private static final Pattern JS_FILE = Pattern.compile("FAIL\\s+([\\w./\\-]+\\.(?:spec|test)\\.\\w+)");

    /**
     * Maven 的 run 级汇总（不带 {@code <<<}）。
     * 逐类汇总（带 {@code -- in Xxx}）是它的子集，两者相加会重复计数——
     * 现场就出现过 1+1+1+3=6 而实际只有 3 的情况。
     */
    private static final Pattern MAVEN_TOTAL = Pattern.compile(
            "^(?![^\\n]*<<<)(?:\\[(?:ERROR|INFO)\\]\\s*)?Tests run:\\s*\\d+,\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*\\d+,\\s*Skipped:\\s*\\d+\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** pytest 没有汇总行，只能把各次 {@code N failed} 相加 */
    private static final Pattern DECLARED_FAILED = Pattern.compile("\\b(\\d+)\\s+(?:failed|failures)\\b");

    private LogDigestParser() {
    }

    /** 证据等级，粒度即可信度。META 是「记账行」，不进入摘要。 */
    private enum Evidence {
        AUTHORITATIVE, PRIMARY, DETAIL, META
    }

    private record Rule(String category, Evidence evidence, Pattern pattern) {
    }

    private static Rule authoritative(String category, String regex) {
        return new Rule(category, Evidence.AUTHORITATIVE, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static Rule primary(String category, String regex) {
        return new Rule(category, Evidence.PRIMARY, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static Rule detail(String category, String regex) {
        return new Rule(category, Evidence.DETAIL, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** 记账行：Maven 的「Tests run: N, Failures: 1 ... -- in ClassName」只说明哪个类挂了，
     *  真正的失败内容在下一行，两者都不该占据摘要位。 */
    private static Rule meta(String category, String regex) {
        return new Rule(category, Evidence.META, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** 顺序即优先级：越具体的框架特征越靠前，通用 {@code ##[error]} 兜底。 */
    private static final List<Rule> RULES = List.of(
            // ---- 测试框架的权威汇总（只认 run 级：不带 <<<）----
            authoritative("test", "^(?![^\\n]*<<<)(?:\\[(?:ERROR|INFO)\\]\\s*)?Tests run:\\s*\\d+,\\s*Failures:\\s*[1-9]\\d*"),
            authoritative("test", "^(?![^\\n]*<<<)(?:\\[(?:ERROR|INFO)\\]\\s*)?Tests run:\\s*\\d+,\\s*Failures:\\s*\\d+,\\s*Errors:\\s*[1-9]"),
            authoritative("test", "^\\d+\\s+(?:failed|failures)\\b"),
            authoritative("test", "^=+ .*\\bfailures\\b.* =+$"),

            // ---- 类级汇总：只说明哪个测试类挂了，正文在下一行 ----
            meta("test", "^\\[ERROR\\]\\s*Tests run:.*<<< (FAILURE|ERROR)!"),

            // ---- 测试失败的具体条目 ----
            primary("test", "^\\[ERROR\\]\\s+[\\w.$]+\\.\\w+\\s+--\\s+Time elapsed"),
            primary("test", "^FAILED\\s+\\S+\\.py"),
            primary("test", "^(✕|×)\\s"),
            primary("test", "^FAIL\\s+\\S+\\.(spec|test)\\."),
            primary("test", "^---\\s+FAIL:"),
            detail("test", "AssertionFailedError|ComparisonFailure|AssertionError|^\\s*E\\s+assert\\b|^\\s*assert\\b"),
            detail("test", "^Wanted but not invoked|^Expecting .* but was|^Expected .* but was"),

            // ---- 构建 / 编译 ----
            primary("build", "^\\[ERROR\\]\\s+Failed to execute goal"),
            primary("build", "\\berror\\s+TS\\d+"),
            primary("build", "compilation failure|build failure"),
            detail("build", "cannot find symbol|symbol not found"),

            // ---- 依赖 ----
            primary("dependency", "^npm (ERR!|error)"),
            primary("dependency", "could not find a version that satisfies|no matching distribution"),
            primary("dependency", "modulenotfounderror|importerror: no module"),
            primary("dependency", "could not resolve dependencies|could not resolve artifact"),
            // setup-node/setup-python 找不到锁文件：job 在准备阶段就挂，测试根本没跑
            primary("dependency", "unable to cache dependencies|dependencies lock file is not found"),
            primary("dependency", "npm ci can only install packages when your"),
            detail("dependency", "cannot find module '[^']+'"),

            // ---- 超时 ----
            primary("timeout", "exceeded the maximum execution time|has timed out|operation timed out"),

            // ---- 权限 / 认证（WARN 级别的这类行会被降级，见 demoteWarnNoise）----
            primary("permission", "resource not accessible|bad credentials|permission denied"),
            detail("permission", "\\b(401|403)\\b unauthorized|forbidden"),

            // ---- 网络 ----
            primary("network", "could not resolve host|temporary failure in name resolution"),
            detail("network", "connection refused|econnreset|etimedout"),

            // ---- 通用兜底：GitHub 自己打的错误标记 ----
            primary("error", "^##\\[error\\]"));

    /** 给 failureClass 投票时的并列决胜顺序（越靠前越具体） */
    private static final List<String> CLASS_PRIORITY =
            List.of("test", "build", "dependency", "timeout", "permission", "network");

    public static LogDigest parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LogDigest.empty();
        }

        String[] rawLines = raw.split("\n", -1);
        int total = rawLines.length;

        // ---- ① 归一化：去掉时间戳前缀，标记 group 结构行 ----
        String[] text = new String[total];
        boolean[] structural = new boolean[total];
        for (int i = 0; i < total; i++) {
            String stripped = TIMESTAMP.matcher(rawLines[i]).replaceFirst("").strip();
            text[i] = stripped;
            structural[i] = stripped.startsWith("##[group]")
                    || stripped.startsWith("##[endgroup]")
                    || stripped.startsWith("##[section]")
                    || stripped.startsWith("##[command]");
        }

        // ---- ② 超长日志只分析首尾窗口，行号保持不变 ----
        List<Integer> window = windowIndexes(total);

        // ---- ③ 逐行匹配 ----
        List<Hit> rawHits = new ArrayList<>();
        for (int idx : window) {
            if (structural[idx] || text[idx].isEmpty()) {
                continue;
            }
            Rule matched = matchRule(text[idx]);
            if (matched != null && matched.evidence() != Evidence.META) {
                rawHits.add(new Hit(idx, matched.category(), matched.evidence(), false));
            }
        }
        if (rawHits.isEmpty()) {
            return new LogDigest("success", "none", "未发现错误输出",
                    new Stats(total, 0, total, 0), List.of());
        }

        // ---- ④ 权威测试汇总存在时锁定类别：其余命中只是这段测试的输出 ----
        boolean testLocked = rawHits.stream()
                .anyMatch(h -> h.evidence() == Evidence.AUTHORITATIVE && "test".equals(h.category()));

        // ---- ⑤ WARN/INFO 级别的权限/网络行降级为细节 ----
        //     它们的典型来源是「被测代码在打印预期内的鉴权失败」，不是真的环境问题。
        //     判据是「别处存在非 WARN 的强证据」——只要日志里已经有别的硬错误，
        //     这些 WARN 就更像是被调用方的输出；反之若整份日志只有它们，则保留。
        java.util.Set<String> strongCategories = new java.util.HashSet<>();
        for (Hit h : rawHits) {
            if (h.evidence() != Evidence.DETAIL && !isWarnLine(text[h.line()])) {
                strongCategories.add(h.category());
            }
        }

        // 权威汇总行只用来定类别和数量；只要有具体错误，它就不该占据摘要位
        boolean hasConcrete = rawHits.stream().anyMatch(h -> h.evidence() == Evidence.PRIMARY);

        List<Hit> hits = new ArrayList<>(rawHits.size());
        for (Hit h : rawHits) {
            boolean excluded = (testLocked && !"test".equals(h.category()))
                    || (h.evidence() == Evidence.AUTHORITATIVE && hasConcrete);
            // 判定为噪声时必须直接排除，而不是降级成 detail ——
            // detail 有一条「该类别没有更强证据时仍展示」的兜底，降级会被它捞回来。
            if (!excluded && h.evidence() == Evidence.PRIMARY
                    && isWarnLine(text[h.line()])
                    && ("permission".equals(h.category()) || "network".equals(h.category()))
                    && !strongCategories.isEmpty()) {
                excluded = true;
            }
            hits.add(new Hit(h.line(), h.category(), h.evidence(), excluded));
        }

        // ---- ⑥ 同类别已有明确证据时，细节行不单算一条 ----
        java.util.Set<String> withEvidence = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (h.evidence() != Evidence.DETAIL) {
                withEvidence.add(h.category());
            }
        }
        List<Hit> effective = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            if (h.excluded()) {
                continue;
            }
            if (h.evidence() == Evidence.DETAIL && withEvidence.contains(h.category())) {
                continue;
            }
            effective.add(h);
        }

        // ---- ⑦ 主错误行开新块，细节并入上一块 ----
        List<Block> blocks = group(effective);

        // ---- ⑧ 按归一化签名去重（跨块统计出现次数） ----
        Map<String, Block> distinct = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Block block : blocks) {
            String signature = signatureOf(text[block.startLine()]);
            occurrences.merge(signature, 1, Integer::sum);
            distinct.putIfAbsent(signature, block);
        }

        // ---- ⑨ 有具体错误时，exit code 收尾行不占摘要位 ----
        boolean hasSpecific = distinct.values().stream()
                .anyMatch(block -> !"error".equals(block.category()));

        String failureClass = testLocked ? "test" : vote(distinct);

        List<Highlight> highlights = new ArrayList<>();
        for (Map.Entry<String, Block> entry : distinct.entrySet()) {
            if (hasSpecific && "error".equals(entry.getValue().category())) {
                continue;
            }
            highlights.add(buildHighlight(entry.getValue(), text, total,
                    occurrences.getOrDefault(entry.getKey(), 1)));
        }
        // 与最终类别同类的排前面：避免噪声占据用户第一眼看到的位置
        highlights.sort(Comparator
                .comparing((Highlight h) -> !h.category().equals(failureClass))
                .thenComparingInt(Highlight::lineNo));
        if (highlights.size() > MAX_HIGHLIGHTS) {
            highlights = new ArrayList<>(highlights.subList(0, MAX_HIGHLIGHTS));
        }

        // ---- ⑩ 统计与结论 ----
        BitSet shown = new BitSet(total);
        for (Highlight h : highlights) {
            shown.set(Math.max(0, h.lineNo() - 1 - CONTEXT_BEFORE),
                    Math.min(total - 1, h.lineNo() - 1 + CONTEXT_AFTER) + 1);
        }
        int collapsed = Math.max(0, total - shown.cardinality());

        String headline = headline(failureClass, distinct, text, highlights,
                declaredTestFailures(text, window));

        return new LogDigest("failure", failureClass, headline,
                new Stats(total, effective.size(), collapsed, distinct.size()), highlights);
    }

    // ------------------------------------------------------------------ 内部

    private record Hit(int line, String category, Evidence evidence, boolean excluded) {
    }

    private record Block(int startLine, String category) {
    }

    /**
     * runner / 构建工具输出的记账行：出现即代表「这段错误已经讲完」。
     *
     * <p>典型的是 {@code Post job cleanup.}、{@code [command]/usr/bin/git version}、
     * {@code [INFO] ...}、{@code ##[endgroup]}——它们不是错误内容，
     * 但紧跟在错误后面，不掐断就会被拼进摘要正文，读起来莫名其妙。
     */
    private static boolean isBookkeeping(String line) {
        return line.startsWith("[INFO]")
                || line.startsWith("[WARNING]")
                || line.startsWith("[command]")
                || line.startsWith("##[")
                || line.startsWith("Post job cleanup");
    }

    private static boolean isWarnLine(String line) {
        Matcher matcher = LOG_LEVEL.matcher(line);
        return matcher.find();
    }

    private static List<Integer> windowIndexes(int total) {
        if (total <= HEAD_LINES + TAIL_LINES) {
            List<Integer> all = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                all.add(i);
            }
            return all;
        }
        List<Integer> window = new ArrayList<>(HEAD_LINES + TAIL_LINES);
        for (int i = 0; i < HEAD_LINES; i++) {
            window.add(i);
        }
        for (int i = total - TAIL_LINES; i < total; i++) {
            window.add(i);
        }
        return window;
    }

    private static Rule matchRule(String line) {
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(line).find()) {
                return rule;
            }
        }
        return null;
    }

    private static List<Block> group(List<Hit> hits) {
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (Hit hit : hits) {
            boolean attach = hit.evidence() == Evidence.DETAIL
                    && current != null
                    && hit.line() - current.startLine() <= DETAIL_ATTACH_GAP;
            if (attach) {
                continue;
            }
            current = new Block(hit.line(), hit.category());
            blocks.add(current);
        }
        return blocks;
    }

    private static String signatureOf(String line) {
        return BLANK.matcher(DIGITS.matcher(line).replaceAll("#")).replaceAll(" ").strip();
    }

    private static Highlight buildHighlight(Block block, String[] text, int total, int occurrences) {
        int from = Math.max(0, block.startLine() - CONTEXT_BEFORE);
        int to = Math.min(total - 1, block.startLine() + CONTEXT_AFTER);
        List<String> context = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            context.add(text[i]);
        }
        String detail = display(block.category(), text[block.startLine()]);
        String followUp = followUp(block.startLine(), text, total);
        if (followUp != null) {
            detail = detail + " · " + followUp;
        }
        return new Highlight("error", block.category(),
                titleOf(block.category()), truncate(detail),
                block.startLine() + 1, blockEnd(block.startLine(), text, total) + 1,
                occurrences, context);
    }

    /**
     * 取主错误行下方紧邻的说明文字。
     *
     * <p>只在往下 3 行内取，且<b>遇到新的主错误就停</b>：pytest 的 FAILED 列表、
     * surefire 的逐类汇总都是一行一条错误，往下取会把下一条的内容拼上来。
     * 这样 Mockito 的「Wanted but not invoked: …」和 JUnit5 的
     * 「Expecting value to be true but was false」都能被带出来。
     */
    private static String followUp(int startLine, String[] text, int total) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = startLine + 1; i < Math.min(total, startLine + 6) && taken < 3; i++) {
            String line = text[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            // 堆栈帧（at java.base/...）对「为什么失败」没有帮助，跳过
            if (line.startsWith("at ") && line.contains("(") && line.endsWith(")")) {
                continue;
            }
            // runner 的记账行：整段错误已经结束，别再往下吞
            if (isBookkeeping(line)) {
                break;
            }
            Rule rule = matchRule(line);
            if (rule != null && rule.evidence() != Evidence.DETAIL) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : " ").append(line);
            taken++;
            if (sb.length() > 240) {
                break;
            }
        }
        return sb.isEmpty() ? null : truncate(sb.toString());
    }

    private static String display(String category, String line) {
        String text = line.replaceFirst("^##\\[error\\]\\s*", "");
        if ("test".equals(category)) {
            // "com.x.Y.method -- Time elapsed: 0.013 s <<< FAILURE!" 只留方法名
            text = text.replaceFirst("^\\[ERROR\\]\\s*", "")
                    .replaceFirst("\\s+--\\s+Time elapsed:.*$", "");
        }
        return text;
    }

    private static String titleOf(String category) {
        return switch (category) {
            case "test" -> "测试失败";
            case "build" -> "构建失败";
            case "dependency" -> "依赖解析失败";
            case "timeout" -> "执行超时";
            case "permission" -> "权限不足";
            case "network" -> "网络异常";
            default -> "错误输出";
        };
    }

    /**
     * 错误块的结束行（0-based，含）。
     *
     * <p>一条错误往往不止一行——Mockito 的「Wanted but not invoked:」后面还跟着
     * 调用签名、{@code -> at ...:134}，再往后才是堆栈。只圈首行会让用户被迫继续往下读。
     *
     * <p>终止条件（任一命中即停）：遇到 {@code [INFO]} / {@code ##[} 这类记账行、
     * 下一条主错误、或超过 40 行。空行不算终止（Mockito 的消息里就有空行分段）。
     */
    private static int blockEnd(int startLine, String[] text, int total) {
        final int maxLines = 40;
        int end = startLine;
        for (int i = startLine + 1; i < total && i <= startLine + maxLines; i++) {
            String line = text[i];
            if (line.isEmpty()) {
                continue;
            }
            if (isBookkeeping(line)) {
                break;
            }
            Rule rule = matchRule(line);
            if (rule != null && rule.evidence() != Evidence.DETAIL) {
                break;
            }
            end = i;
        }
        return end;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_DETAIL ? value : value.substring(0, MAX_DETAIL) + "…";
    }

    private static String vote(Map<String, Block> distinct) {
        Map<String, Integer> counts = new HashMap<>();
        for (Block block : distinct.values()) {
            if (!"error".equals(block.category())) {
                counts.merge(block.category(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "unknown";
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount || (entry.getValue() == bestCount
                    && CLASS_PRIORITY.indexOf(entry.getKey()) < CLASS_PRIORITY.indexOf(best))) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best == null ? "unknown" : best;
    }

    /**
     * 测试失败数取「测试框架自己报的数」，而不是数我们的 block。
     *
     * <p>Maven 的 run 级汇总已经包含所有测试类，取<b>最大值</b>；求和会把子集重复算进去。
     * pytest 没有 run 级汇总，只能把各次 {@code N failed} 相加。拿不到时返回 null，
     * 由调用方降级为「数 block」。
     */
    private static Integer declaredTestFailures(String[] text, List<Integer> window) {
        int max = -1;
        for (int idx : window) {
            Matcher maven = MAVEN_TOTAL.matcher(text[idx]);
            if (maven.find()) {
                max = Math.max(max, Integer.parseInt(maven.group(1)));
            }
        }
        if (max > 0) {
            return max;
        }

        int sum = 0;
        boolean found = false;
        for (int idx : window) {
            Matcher pytest = DECLARED_FAILED.matcher(text[idx]);
            if (pytest.find()) {
                sum += Integer.parseInt(pytest.group(1));
                found = true;
            }
        }
        return found ? sum : null;
    }

    private static String headline(String failureClass, Map<String, Block> distinct, String[] text,
                                   List<Highlight> highlights, Integer declaredFailures) {
        int sameClass = 0;
        for (Block block : distinct.values()) {
            if (block.category().equals(failureClass)) {
                sameClass++;
            }
        }

        String where = "";
        String file = firstTestFile(distinct, text);
        if (file != null) {
            where = "，集中在 " + file;
        }

        return switch (failureClass) {
            case "test" -> {
                int count = declaredFailures != null && declaredFailures > 0 ? declaredFailures : sameClass;
                yield count + " 处测试失败" + where;
            }
            case "build" -> "构建失败" + where;
            case "dependency" -> "依赖解析失败" + where;
            case "timeout" -> "执行超时被终止";
            case "permission" -> "权限不足，无法访问所需资源";
            case "network" -> "网络异常，无法连接外部服务";
            // 认不出来时不要拿某一行顶替结论：之前把 setup-node 的
            // "unable to cache dependencies" 当成失败原因展示，读起来像天书
            default -> "日志中出现错误输出，未能归类";
        };
    }

    private static String firstTestFile(Map<String, Block> distinct, String[] text) {
        for (Block block : distinct.values()) {
            if (!"test".equals(block.category())) {
                continue;
            }
            Matcher py = PY_FILE.matcher(text[block.startLine()]);
            if (py.find()) {
                return py.group(1);
            }
            Matcher js = JS_FILE.matcher(text[block.startLine()]);
            if (js.find()) {
                return js.group(1);
            }
        }
        return null;
    }
}
