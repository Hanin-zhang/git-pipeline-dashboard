package com.githublook.webhook;

import com.githublook.webhook.dto.WebhookAck;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single inbound endpoint GitHub posts to. It answers in milliseconds: signature check,
 * delivery id claim, Redis push. Nothing heavy runs on this thread.
 */
@RestController
@RequestMapping({"${app.webhook.path:/api/webhooks/github}", "${app.webhook.path:/api/webhooks/github}/"})
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookIngestService ingestService;

    @PostMapping
    public ResponseEntity<WebhookAck> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) byte[] body) {

        WebhookAck ack = ingestService.ingest(event, deliveryId, signature, body);
        HttpStatus status = "QUEUED".equals(ack.status()) || "PONG".equals(ack.status())
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(ack);
    }
}
