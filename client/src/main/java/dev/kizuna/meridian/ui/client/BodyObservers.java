package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.RestoreStatus;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 一个连接上的身体观察者共享宿主订阅；匹配回执与新快照全部到达才发布。 */
final class BodyObservers {
    private final Map<WindowState, Consumer<RestoreStatus>> listeners = new LinkedHashMap<>();
    private String request = "";
    private boolean acknowledged;
    private boolean received;
    private boolean live;

    void add(WindowState window, Consumer<RestoreStatus> status, Runnable subscribe, Runnable release) {
        // 首开订阅，末关退订；后续窗口共享已经确认过的同连接身体。
        listeners.put(window, status);
        window.scope().addCleanup(() -> {
            if (listeners.remove(window) != null && listeners.isEmpty()) {
                reset();
                release.run();
            }
        });
        if (listeners.size() == 1) {
            subscribe.run();
        } else if (live) {
            status.accept(RestoreStatus.LIVE);
        }
    }

    boolean active() {
        return !listeners.isEmpty();
    }

    void sent(String id) {
        // 上一个请求的回执不能解锁新的窗口观察。
        request = id;
        received = false;
        acknowledged = false;
        live = false;
    }

    void receipt(String id, RestoreStatus status) {
        // 对无关请求的批准或拒绝都保持当前观察状态。
        if (request.isEmpty() || !request.equals(id)) {
            return;
        }
        if (status == RestoreStatus.DENIED) {
            reset();
            notifyAll(status);
            return;
        }
        acknowledged = true;
        publish();
    }

    void snapshot() {
        // 身体推送无请求号，记录发出订阅以后的内观快照，并与具名确认配对。
        if (!request.isEmpty()) {
            received = true;
            publish();
        }
    }

    private void publish() {
        // 两个包可以不同序，后续推送直接更新模块持有的身体。
        if (received && acknowledged && !live) {
            live = true;
            notifyAll(RestoreStatus.LIVE);
        }
    }

    private void notifyAll(RestoreStatus status) {
        // 拒绝回调可能立即关闭 scope。
        List.copyOf(listeners.values()).forEach(listener -> listener.accept(status));
    }

    private void reset() {
        request = "";
        received = false;
        acknowledged = false;
        live = false;
    }

    void disconnect() {
        // 窗口随后释放时不向旧连接发退订。
        listeners.clear();
        reset();
    }
}
