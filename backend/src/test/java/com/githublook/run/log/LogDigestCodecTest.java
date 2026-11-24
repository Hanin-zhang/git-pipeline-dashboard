package com.githublook.run.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogDigestCodec · 摘要的压缩存储与版本校验")
class LogDigestCodecTest {

    private final LogDigestCodec codec = new LogDigestCodec(new ObjectMapper());

    private static LogDigest sample() {
        return new LogDigest("failure", "test", "2 处测试失败，集中在 tests/a.py",
                new LogDigest.Stats(300, 7, 260, 3),
                List.of(new LogDigest.Highlight("error", "test", "测试失败",
                        "FAILED tests/a.py::test_x", 25, 27, 2,
                        List.of("line-1", "line-2", "line-3"))));
    }

    @Test
    @DisplayName("编码后可以完整还原结论与错误块")
    void roundTrip() {
        String json = codec.encode(sample());
        assertThat(json).isNotBlank();

        Optional<LogDigest> decoded = codec.decode(json, LogDigestCodec.VERSION);

        assertThat(decoded).isPresent();
        LogDigest digest = decoded.get();
        assertThat(digest.verdict()).isEqualTo("failure");
        assertThat(digest.failureClass()).isEqualTo("test");
        assertThat(digest.headline()).isEqualTo("2 处测试失败，集中在 tests/a.py");
        assertThat(digest.stats().totalLines()).isEqualTo(300);
        assertThat(digest.highlights()).hasSize(1);
        assertThat(digest.highlights().get(0).lineNo()).isEqualTo(25);
        assertThat(digest.highlights().get(0).endLine()).isEqualTo(27);
        assertThat(digest.highlights().get(0).endLine()).isEqualTo(27);
        assertThat(digest.highlights().get(0).occurrences()).isEqualTo(2);
    }

    @Test
    @DisplayName("落库时丢掉上下文，控制单条摘要体积")
    void stripsContext() {
        LogDigest decoded = codec.decode(codec.encode(sample()), LogDigestCodec.VERSION).orElseThrow();

        assertThat(decoded.highlights().get(0).context()).isEmpty();
        // 原始对象不受影响
        assertThat(sample().highlights().get(0).context()).hasSize(3);
    }

    @Test
    @DisplayName("版本不一致视为未命中，促使调用方按新规则重算")
    void versionMismatchIsMiss() {
        String json = codec.encode(sample());

        assertThat(codec.decode(json, LogDigestCodec.VERSION + 1)).isEmpty();
        assertThat(codec.decode(json, null)).isEmpty();
    }

    @Test
    @DisplayName("空值或损坏的 JSON 不抛异常，按未命中处理")
    void toleratesBadInput() {
        assertThat(codec.decode(null, LogDigestCodec.VERSION)).isEmpty();
        assertThat(codec.decode("   ", LogDigestCodec.VERSION)).isEmpty();
        assertThat(codec.decode("{not-json", LogDigestCodec.VERSION)).isEmpty();
        assertThat(codec.encode(null)).isNull();
    }
}
