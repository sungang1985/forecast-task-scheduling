package com.forecast.task.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务进度实时推送（Server-Sent Events）。前端/调用方订阅后可实时看到步骤切换与引擎侧进度，
 * 任务结束自动关闭连接。多实例部署时，事件来自执行该任务的节点，可在网关层按任务做会话粘滞，或回退为轮询详情接口。
 */
@Slf4j
@Component
public class TaskEventStreamService {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String taskId, Object snapshot, boolean finished) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        if (finished) {
            emitter.complete();
            return emitter;
        }
        List<SseEmitter> list = emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            List<SseEmitter> current = emitters.get(taskId);
            if (current != null) {
                current.remove(emitter);
                if (current.isEmpty()) {
                    emitters.remove(taskId, current);
                }
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    @EventListener
    public void onTaskEvent(TaskEvent event) {
        List<SseEmitter> list = emitters.get(event.taskId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event.type().name().toLowerCase()).data(event));
                if (event.isFinished()) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
        if (event.isFinished()) {
            emitters.remove(event.taskId());
        }
    }

    public int subscriberCount() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }
}
