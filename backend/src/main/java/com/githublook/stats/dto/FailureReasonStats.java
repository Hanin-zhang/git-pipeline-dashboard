package com.githublook.stats.dto;

import java.util.List;

/**
 * 失败原因分布。
 *
 * @param totalFailed  窗口内的失败运行总数
 * @param classified   已经归类的数量
 * @param unclassified 还没归类的数量（没配 Token，或没人点开过那次运行的日志）
 * @param known        是否具备自动归类能力（配置了 GitHub Token）
 * @param reasons      按数量倒序的各个类别
 */
public record FailureReasonStats(
        long totalFailed,
        long classified,
        long unclassified,
        boolean known,
        List<Reason> reasons) {

    /** @param percent 占「已归类」的比例，便于前端画条 */
    public record Reason(String failureClass, String label, long count, double percent) {
    }
}
