package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import java.util.*;

/** 连接级的只读反馈；所有窗口共用事件时间，重复回执不会再次爆亮或播音。 */
public final class OpeningFeedback {
    public enum Outcome { COMPLETED, INTERRUPTED }
    public record Flash(Outcome outcome, double progress, long atNanos) { }
    public record Event(String meridian, Outcome outcome) { }
    private BodySnapshot previous;
    private final Map<String, Flash> flashes = new LinkedHashMap<>();

    public List<Event> receive(BodySnapshot body, long now) {
        // 初次同步与换身体只建立基线，旧存档的已开经脉不能当作刚刚完成。
        if (previous == null || !previous.getId().equals(body.getId())) {
            clear();
            previous = body;
            return List.of();
        }
        if (body.getRevision() <= previous.getRevision()) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        for (MeridianState state : body.getMeridianStatesList()) {
            MeridianState old = previous.getMeridianStatesList().stream()
                    .filter(item -> item.getId().equals(state.getId())).findFirst().orElse(null);
            if (old != null && old.getOpening().getStage() != MeridianOpeningStage.OPENED
                    && state.getOpening().getStage() == MeridianOpeningStage.OPENED) {
                flashes.put(state.getId(), new Flash(Outcome.COMPLETED, 1, now));
                events.add(new Event(state.getId(), Outcome.COMPLETED));
            }
        }

        // 维持退回准备也意味着本次计时被权威清零，和暂停、损坏、耗竭一样给回退反馈。
        OpeningTask oldTask = previous.getOpeningTask();
        OpeningTask task = body.getOpeningTask();
        boolean sameAttempt = task.getActionInstanceId().equals(oldTask.getActionInstanceId())
                && task.getStartedMillis() == oldTask.getStartedMillis();
        boolean resetHold = oldTask.getStatus() == OpeningTaskStatus.OPENING_HOLDING
                && task.getStatus() == OpeningTaskStatus.OPENING_PREPARING;
        String target = oldTask.getRule().getTargetMeridianId();
        boolean completed = events.stream().anyMatch(event -> event.meridian().equals(target));
        if (active(oldTask) && (!sameAttempt || !active(task) || resetHold) && !completed) {
            double progress = QiDisplay.ratio(oldTask.getHeldMillis(), oldTask.getRequiredMillis());
            flashes.put(target, new Flash(Outcome.INTERRUPTED, progress, now));
            events.add(new Event(target, Outcome.INTERRUPTED));
        }
        previous = body;
        flashes.entrySet().removeIf(entry -> now - entry.getValue().atNanos() > 2_400_000_000L);
        return List.copyOf(events);
    }

    public Flash flash(String id, long now) {
        // 本地时间只控制已确认结果的短暂反馈，不推进权威开脉进度。
        Flash flash = flashes.get(id);
        return flash != null && now - flash.atNanos() < 2_400_000_000L ? flash : null;
    }

    public static boolean active(OpeningTask task) {
        // 尚在准备和维持的任务都应可见，暂停与结果状态不继续流光。
        return task.getStatus() == OpeningTaskStatus.OPENING_PREPARING
                || task.getStatus() == OpeningTaskStatus.OPENING_HOLDING;
    }

    public void clear() {
        // 断线和换身清掉旧事件，避免下一连接复用音效或闪光。
        previous = null;
        flashes.clear();
    }
}
