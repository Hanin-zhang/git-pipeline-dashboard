package com.githublook.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebhookSignatureVerifier · HMAC-SHA256 验签")
class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();
    private final byte[] payload = "{\"repository\":{\"full_name\":\"acme/app\"}}"
            .getBytes(StandardCharsets.UTF_8);
    private final String secret = "hanin-github";

    @Test
    @DisplayName("自己签的名可以通过校验（签名与验签链路闭环）")
    void acceptsSelfSignedPayload() {
        String signature = verifier.sign(payload, secret);

        assertThat(signature).startsWith("sha256=");
        assertThat(verifier.isValid(payload, signature, secret)).isTrue();
    }

    @Test
    @DisplayName("签名必须匹配 GitHub 官方格式：sha256= + 64 位十六进制")
    void producesGitHubCompatibleFormat() {
        String signature = verifier.sign(payload, secret);

        assertThat(signature).matches("^sha256=[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("换一个 secret 即校验失败")
    void rejectsSignatureFromDifferentSecret() {
        String signature = verifier.sign(payload, "another-secret");

        assertThat(verifier.isValid(payload, signature, secret)).isFalse();
    }

    @Test
    @DisplayName("payload 被篡改（哪怕一个字节）即校验失败")
    void rejectsTamperedPayload() {
        String signature = verifier.sign(payload, secret);
        byte[] tampered = "{\"repository\":{\"full_name\":\"acme/evil\"}}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(tampered, signature, secret)).isFalse();
    }

    @Test
    @DisplayName("只支持 sha256= 前缀，sha1= 等旧方案直接拒绝")
    void rejectsUnsupportedScheme() {
        assertThat(verifier.isValid(payload, "sha1=abcdef", secret)).isFalse();
    }

    @Test
    @DisplayName("非十六进制的签名不会被当成异常抛出，而是安静拒绝")
    void rejectsNonHexSignature() {
        assertThat(verifier.isValid(payload, "sha256=not-a-hex-value", secret)).isFalse();
    }

    @Test
    @DisplayName("签名长度不足同样拒绝（不会被截断比较误判）")
    void rejectsTruncatedSignature() {
        String full = verifier.sign(payload, secret);

        assertThat(verifier.isValid(payload, full.substring(0, 20), secret)).isFalse();
    }

    @Test
    @DisplayName("前缀前后空白可以被容忍")
    void trimsSurroundingWhitespace() {
        String signature = "  " + verifier.sign(payload, secret) + "\n";

        assertThat(verifier.isValid(payload, signature, secret)).isTrue();
    }

    @Test
    @DisplayName("空 secret / 空 header / 空 body 一律拒绝，避免「未配置密钥 = 放行」")
    void rejectsMissingInputs() {
        String signature = verifier.sign(payload, secret);

        assertThat(verifier.isValid(payload, signature, "")).isFalse();
        assertThat(verifier.isValid(payload, signature, null)).isFalse();
        assertThat(verifier.isValid(payload, null, secret)).isFalse();
        assertThat(verifier.isValid(null, signature, secret)).isFalse();
    }

    @Test
    @DisplayName("十六进制部分大小写不敏感（GitHub 下发小写，但解析不应因此失败）")
    void acceptsUppercaseHex() {
        String signature = "sha256=" + verifier.sign(payload, secret).substring(7).toUpperCase();

        assertThat(verifier.isValid(payload, signature, secret)).isTrue();
    }

    @Test
    @DisplayName("compute 对同样的输入是确定性的")
    void computeIsDeterministic() {
        assertThat(verifier.compute(payload, secret)).isEqualTo(verifier.compute(payload, secret));
    }

    @Test
    @DisplayName("签名计算不使用平台默认字符集（中文 secret 也稳定）")
    void handlesNonAsciiSecret() {
        String cnSecret = "看板密钥-中文-secret";
        String signature = verifier.sign(payload, cnSecret);

        assertThat(verifier.isValid(payload, signature, cnSecret)).isTrue();
        assertThat(verifier.isValid(payload, signature, secret)).isFalse();
    }

    @Test
    @DisplayName("空 payload 也能算出确定签名（不会 NPE）")
    void handlesEmptyPayload() {
        byte[] empty = new byte[0];

        assertThat(verifier.isValid(empty, verifier.sign(empty, secret), secret)).isTrue();
    }
}
