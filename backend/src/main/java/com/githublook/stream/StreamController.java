package com.githublook.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class StreamController {

    private final SseHub hub;

    /**
     * 长连接事件流。
     *
     * <p>{@code cid} 是浏览器生成的标签页标识（存在 sessionStorage，刷新后不变）。
     * 不带也能用，但那时每次刷新都会新建一条连接，旧的只能等写出失败才被回收，
     * 连接数会虚高，所以前端总是会带上它。
     */
    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "cid", required = false) String cid) {
        return hub.subscribe(cid);
    }
}
