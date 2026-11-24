package com.githublook.stats;

import com.githublook.stats.dto.FailureReasonStats;
import com.githublook.stats.model.FailureReasonRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FailureReasonAssembler · 失败原因分布口径")
class FailureReasonAssemblerTest {

    private static FailureReasonRow row(String cls, long total) {
        FailureReasonRow r = new FailureReasonRow();
        r.setFailureClass(cls);
        r.setTotal(total);
        return r;
    }

    @Test
    @DisplayName("没有任何失败时返回全零，不抛除零异常")
    void emptyRows() {
        FailureReasonStats stats = FailureReasonAssembler.assemble(List.of(), true);

        assertThat(stats.totalFailed()).isZero();
        assertThat(stats.classified()).isZero();
        assertThat(stats.unclassified()).isZero();
        assertThat(stats.reasons()).isEmpty();
    }

    @Test
    @DisplayName("null 行列表按空处理")
    void nullRows() {
        assertThat(FailureReasonAssembler.assemble(null, false).totalFailed()).isZero();
    }

    @Test
    @DisplayName("百分比以「已归类」为分母，未归类单独统计")
    void percentExcludesUnclassified() {
        FailureReasonStats stats = FailureReasonAssembler.assemble(List.of(
                row("test", 7),
                row("dependency", 2),
                row(null, 3)), true);

        assertThat(stats.totalFailed()).isEqualTo(12);
        assertThat(stats.classified()).isEqualTo(9);
        assertThat(stats.unclassified()).isEqualTo(3);

        assertThat(stats.reasons()).extracting(FailureReasonStats.Reason::failureClass)
                .containsExactly("test", "dependency");
        assertThat(stats.reasons().get(0).percent()).isEqualTo(77.8);
        assertThat(stats.reasons().get(1).percent()).isEqualTo(22.2);
    }

    @Test
    @DisplayName("全部未归类时百分比为 0，而不是除以 0 崩掉")
    void allUnclassified() {
        FailureReasonStats stats = FailureReasonAssembler.assemble(List.of(row(null, 5)), true);

        assertThat(stats.classified()).isZero();
        assertThat(stats.unclassified()).isEqualTo(5);
        assertThat(stats.reasons()).isEmpty();
    }

    @Test
    @DisplayName("已知类别翻译成中文标签，未知类别原样保留")
    void labels() {
        FailureReasonStats stats = FailureReasonAssembler.assemble(List.of(
                row("test", 1),
                row("timeout", 1),
                row("brand_new_class", 1)), true);

        assertThat(stats.reasons()).extracting(FailureReasonStats.Reason::label)
                .containsExactly("测试失败", "执行超时", "brand_new_class");
    }

    @Test
    @DisplayName("保持 SQL 给出的顺序（已按数量倒序），不在这里重排")
    void keepsIncomingOrder() {
        FailureReasonStats stats = FailureReasonAssembler.assemble(List.of(
                row("build", 9),
                row("test", 3)), true);

        assertThat(stats.reasons()).extracting(FailureReasonStats.Reason::failureClass)
                .containsExactly("build", "test");
    }

    @Test
    @DisplayName("数量为 0 或负数、空白类别的行被忽略")
    void ignoresNoise() {
        FailureReasonStats stats = FailureReasonAssembler.assemble(List.of(
                row("test", 0),
                row("  ", 4),
                row("build", 2)), true);

        assertThat(stats.reasons()).hasSize(1);
        assertThat(stats.reasons().get(0).failureClass()).isEqualTo("build");
        assertThat(stats.unclassified()).isEqualTo(4);
    }

    @Test
    @DisplayName("把「是否配了 Token」透传给前端，用于展示未归类的原因提示")
    void passesGithubFlag() {
        assertThat(FailureReasonAssembler.assemble(List.of(), true).known()).isTrue();
        assertThat(FailureReasonAssembler.assemble(List.of(), false).known()).isFalse();
    }
}
