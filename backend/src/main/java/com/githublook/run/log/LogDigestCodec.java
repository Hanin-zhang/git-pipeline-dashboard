package com.githublook.run.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 摘要的序列化与版本管理。
 *
 * <p>落库时不存 {@code context} —— 它占了摘要九成的体积，而用户真要看上下文时
 * 本来就该去看全文。去掉之后一份摘要通常只有 1~2KB。
 *
 * <p>{@link #VERSION} 是解析规则的版本号：规则一改就 +1，旧数据读取时视为未命中，
 * 从而在下次访问时自动重算。这比写迁移脚本去重刷历史数据可靠得多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogDigestCodec {

    public static final int VERSION = 4;

    private final ObjectMapper objectMapper;

    /** 压缩存储形态：保留结论与错误块，丢掉上下文 */
    public String encode(LogDigest digest) {
        if (digest == null) {
            return null;
        }
        LogDigest compact = new LogDigest(
                digest.verdict(),
                digest.failureClass(),
                digest.headline(),
                digest.stats(),
                digest.highlights().stream()
                        .map(h -> new LogDigest.Highlight(h.severity(), h.category(), h.title(),
                                h.detail(), h.lineNo(), h.endLine(), h.occurrences(), List.of()))
                        .toList());
        try {
            return objectMapper.writeValueAsString(compact);
        } catch (Exception e) {
            log.warn("digest_encode_failed error={}", e.toString());
            return null;
        }
    }

    /**
     * 不校验版本地读取。
     *
     * <p>用于「旧版结论」的兜底展示：版本落后时仍然把内容读出来，由调用方
     * 标记为 stale 降级展示，而不是假装什么都没有。
     */
    public Optional<LogDigest> decodeAny(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, LogDigest.class));
        } catch (Exception e) {
            log.warn("digest_decode_any_failed error={}", e.toString());
            return Optional.empty();
        }
    }

    /** 版本不匹配或内容损坏都当作未命中，让调用方回源重算。 */
    public Optional<LogDigest> decode(String json, Integer version) {
        if (json == null || json.isBlank() || version == null || version != VERSION) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, LogDigest.class));
        } catch (Exception e) {
            log.warn("digest_decode_failed error={}", e.toString());
            return Optional.empty();
        }
    }
}
