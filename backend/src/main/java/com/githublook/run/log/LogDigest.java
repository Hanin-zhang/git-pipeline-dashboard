package com.githublook.run.log;

import java.util.List;

/**
 * 一份日志的结构化摘要。
 *
 * <p>设计原则：**宁可少说，不可瞎说**。每一条 {@link Highlight} 都必须能通过
 * {@code lineNo} 指回原始日志的某一行，不允许出现二次加工或推测出来的结论。
 *
 * @param verdict      success | failure | unknown —— 只看日志本身，不参考 run 状态
 * @param failureClass test | build | dependency | timeout | permission | network | unknown | none
 * @param headline     一句话结论，直接展示在摘要顶部
 * @param stats        统计信息，用于告诉用户「折叠了多少行」
 * @param highlights   关键错误块（最多 5 条，已按模式去重）
 */
public record LogDigest(
        String verdict,
        String failureClass,
        String headline,
        Stats stats,
        List<Highlight> highlights) {

    /** @param collapsedLines 被摘要折叠掉的无关行数 */
    public record Stats(int totalLines, int errorLines, int collapsedLines, int blockCount) {
    }

    /**
     * @param lineNo      1-based，错误块的起始行号
     * @param endLine     1-based，错误块的结束行号（含）。等于 lineNo 表示只有一行；
     *                    前端据此把「Wanted but not invoked: …」这类多行信息整体圈出来
     * @param occurrences 相同错误模式出现的次数
     * @param context     原始上下文（已去掉时间戳前缀，行号仍指向原文）
     */
    public record Highlight(
            String severity,
            String category,
            String title,
            String detail,
            int lineNo,
            int endLine,
            int occurrences,
            List<String> context) {
    }

    public static LogDigest empty() {
        return new LogDigest("unknown", "none", "该作业没有日志输出",
                new Stats(0, 0, 0, 0), List.of());
    }
}
