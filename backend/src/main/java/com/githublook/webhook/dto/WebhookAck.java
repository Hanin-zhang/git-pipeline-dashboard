package com.githublook.webhook.dto;

/** Acknowledgement returned to GitHub. GitHub only looks at the status code, humans read the body. */
public record WebhookAck(String deliveryId, String status, String message) {
}
