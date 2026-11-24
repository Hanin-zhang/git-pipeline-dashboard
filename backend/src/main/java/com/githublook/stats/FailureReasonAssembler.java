package com.githublook.stats;

import com.githublook.stats.dto.FailureReasonStats;
import com.githublook.stats.model.FailureReasonRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「按 failure_class 分组的计数」组装成前端直接可用的分布。
 *
 * <p>抽成纯函数是为了能脱离 Spring 单测：这里只有口径问题（分母怎么算、
 * 未归类怎么摆），没有 IO。
 */
public final class FailureReasonAssembler {

    private FailureReasonAssembler() {
    }

    public static FailureReasonStats assemble(List<FailureReasonRow> rows, boolean githubEnabled) {
        List<FailureReasonRow> source = rows == null ? List.of() : rows;

        long unclassified = 0;
        long classified = 0;
        List<FailureReasonStats.Reason> reasons = new ArrayList<>(source.size());
        for (FailureReasonRow row : source) {
            if (row == null || row.getTotal() <= 0) {
                continue;
            }
            if (row.getFailureClass() == null || row.getFailureClass().isBlank()) {
                unclassified += row.getTotal();
            } else {
                classified += row.getTotal();
                reasons.add(new FailureReasonStats.Reason(row.getFailureClass(),
                        labelOf(row.getFailureClass()), row.getTotal(), 0));
            }
        }

        // 百分比以「已归类」为分母：未归类的还不知道原因，混进去会把各类占比整体拉低
        List<FailureReasonStats.Reason> scaled = new ArrayList<>(reasons.size());
        for (FailureReasonStats.Reason reason : reasons) {
            double percent = classified == 0 ? 0
                    : Math.round(reason.count() * 1000d / classified) / 10d;
            scaled.add(new FailureReasonStats.Reason(reason.failureClass(), reason.label(),
                    reason.count(), percent));
        }

        return new FailureReasonStats(classified + unclassified, classified, unclassified,
                githubEnabled, scaled);
    }

    /** 类别到中文标签；未知类别原样返回，将来新增分类不至于显示空白 */
    static String labelOf(String failureClass) {
        return switch (failureClass) {
            case "test" -> "测试失败";
            case "build" -> "构建失败";
            case "dependency" -> "依赖问题";
            case "timeout" -> "执行超时";
            case "permission" -> "权限不足";
            case "network" -> "网络异常";
            case "unknown" -> "未能识别";
            default -> failureClass;
        };
    }
}
