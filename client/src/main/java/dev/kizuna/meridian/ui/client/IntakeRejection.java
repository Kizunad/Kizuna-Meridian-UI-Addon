package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 吐纳窗口余力条下面那行「未纳入：经脉已满」：一直纳不进时写出原因（算不算纳不进由 {@link IntakeStall} 判断）。
 * 服务端报过受阻就写受阻的原因；没报过、当地又没有灵气，写此地没有灵气。这里只写一句短的，
 * 怎么办写在按钮和纳入量的悬浮提示里；一行排不下就折行，最多两行。
 */
final class IntakeRejection {
    private static final String PREFIX = "未纳入：";
    private static final String BARREN = "此地没有灵气";
    private static final String OMISSION = "…";
    /** 最多占这么多行，再长的在末行收成省略号。 */
    static final int MAX_LINES = 2;

    private IntakeRejection() {
        // 只从吐纳视图派生文字，不保存状态。
    }

    static List<String> lines(String requestError, String reason, ToIntFunction<String> widthOf, int width) {
        // 请求被整个拒掉时写服务端回的那句；否则写纳不进的原因，原因是空的就什么都不写。
        if (!requestError.isEmpty()) {
            return wrap(requestError, widthOf, width);
        }
        if (reason.isEmpty()) {
            return List.of();
        }
        return wrap(reason, widthOf, width);
    }

    static String reason(FailureCode failure, GatheringEnvironment environment) {
        // 纳入量一直没涨时该写的那句：先看服务端报过的受阻，再看当地有没有灵气；都不是就不写（比如手动吐纳没在吸）。
        if (failure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            return PREFIX + phrase(failure);
        }
        boolean barren = !environment.getAvailable() || environment.getConcentrationPerBlock() <= 0;
        return barren ? PREFIX + BARREN : "";
    }

    private static String phrase(FailureCode code) {
        // 和悬浮提示用同一组失败码，这里只说卡在哪。
        return switch (code) {
            case CAPACITY_EXCEEDED -> "经脉已满";
            case INSUFFICIENT_QI -> "此地灵气不够";
            case ENVIRONMENT_BUDGET_EXCEEDED -> "此地灵气被占用";
            case ENVIRONMENT_UNAVAILABLE -> BARREN;
            case ENVIRONMENT_INTERVAL_INVALID -> "此地灵气一时接不上";
            case MODULE_UNAVAILABLE, ROUTE_UNAVAILABLE -> "肺或经络失能";
            case THROUGHPUT_EXCEEDED -> "经络来不及送";
            case BODY_DEAD -> "身体已经死亡";
            default -> "原因见按钮提示";
        };
    }

    private static List<String> wrap(String text, ToIntFunction<String> widthOf, int width) {
        // 逐字往一行里放，放不下就换行；中文没有空格，按字折。
        List<String> lines = new ArrayList<>();
        var line = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            String letter = Character.toString(codePoint);
            if (!line.isEmpty() && widthOf.applyAsInt(line + letter) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(letter);
        });
        lines.add(line.toString());
        if (lines.size() <= MAX_LINES) {
            return lines;
        }
        return shortened(lines.subList(0, MAX_LINES), widthOf, width);
    }

    private static List<String> shortened(List<String> kept, ToIntFunction<String> widthOf, int width) {
        // 末行去掉几个字，腾出省略号的位置。
        List<String> lines = new ArrayList<>(kept);
        String last = lines.removeLast();
        while (!last.isEmpty() && widthOf.applyAsInt(last + OMISSION) > width) {
            last = last.substring(0, last.offsetByCodePoints(last.length(), -1));
        }
        lines.add(last + OMISSION);
        return lines;
    }
}
