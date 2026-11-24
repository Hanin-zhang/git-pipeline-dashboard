package com.githublook.run;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维接口。只做参数转发，业务在 service 层。
 *
 * <p>鉴权沿用全局规则（{@code anyRequest().authenticated()}）——
 * 本系统只有管理员账号，不再额外引入角色。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DigestBackfillService backfillService;

    /**
     * 手动触发摘要回刷。
     *
     * <p>先用小 limit 验证（例如 5），确认没在打空转再放大。
     */
    @PostMapping("/backfill-digests")
    public DigestBackfillService.Result backfillDigests(
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return backfillService.run(limit);
    }
}
