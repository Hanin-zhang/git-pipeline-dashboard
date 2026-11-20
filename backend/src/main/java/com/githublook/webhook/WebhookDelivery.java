package com.githublook.webhook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Audit row for every webhook we receive. Doubles as the durable side of the
 * idempotency guard (unique index on delivery_id) should Redis lose its keys.
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("webhook_delivery")
public class WebhookDelivery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deliveryId;
    private String eventName;
    /** RECEIVED | QUEUED | PROCESSED | IGNORED | FAILED | DUPLICATE */
    private String status;
    private String repoFullName;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private String errorMessage;
}
