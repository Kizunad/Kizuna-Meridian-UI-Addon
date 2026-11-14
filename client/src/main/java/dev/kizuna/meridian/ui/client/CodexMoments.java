package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.technique.fabric.client.TechniqueScrollClient;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog.Scroll;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 点亮瞬间：读卷学会时弹一张小卡，并把这一格记成「新」，下次翻到那本书时播一次点亮。
 *
 * <p>「新」只记在本次游戏里，看过才清掉。凑齐一本书不另弹小卡，由那本书的进度小卡来报。
 */
final class CodexMoments {
    /** 点亮的一声轻响：紫水晶的风铃声，压低音量。 */
    static final SoundEvent LIT_SOUND = SoundEvents.AMETHYST_BLOCK_CHIME;
    private static final float LIT_PITCH = 1.2f;
    private static final float LIT_VOLUME = 0.8f;
    private static final Map<String, Long> FRESH = new LinkedHashMap<>();
    private static final Set<String> SEEN_PLAYING = new HashSet<>();
    private static CodexCatalog catalog;

    private CodexMoments() {
        // 全局只有一份本地展示状态。
    }

    static void initialize() {
        // 只接卷轴学会这一条路：命令与管理员学会不算收集，不弹卡。
        TechniqueScrollClient.LEARNED.register(CodexMoments::learned);
    }

    static CodexCatalog catalog() {
        // 目录等功法库登记完卷轴物品后再读，之后整局复用。
        if (catalog == null) {
            catalog = CodexCatalog.load();
        }
        return catalog;
    }

    private static void learned(Scroll scroll) {
        // 进度把刚学会的这卷算进去，避免已学列表比学会通知晚到一帧时少算一格；小卡出来时轻响一声。
        String volumeId = scroll.volume().getId();
        var book = catalog().bookOf(volumeId);
        if (book.isEmpty()) {
            return;
        }
        var learned = new HashSet<>(CodexProgress.current().learnedVolumes(catalog()));
        learned.add(volumeId);
        int total = book.get().volumes().size();
        int count = (int) book.get().volumes().stream().filter(volume -> learned.contains(volume.id())).count();
        String line = scroll.displayName() + "　" + count + "/" + total;
        var icon = catalog().volume(volumeId).map(CodexCatalog.Volume::icon).orElseThrow();
        Minecraft.getInstance().gui.toastManager().addToast(new CodexToast(icon, "功法谱 · 新添一卷", line));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(LIT_SOUND, LIT_PITCH, LIT_VOLUME));
        FRESH.put(volumeId, 0L);
    }

    static boolean fresh(String volumeId) {
        // 还没在功法谱里看过的新卷带「新」字。
        return FRESH.containsKey(volumeId);
    }

    static float glow(String volumeId) {
        // 第一次画到这一格时开始计时，剪影在一秒里褪成实色，再亮三秒边框后停在「新」字。
        Long started = FRESH.get(volumeId);
        if (started == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (started == 0L) {
            FRESH.put(volumeId, now);
            SEEN_PLAYING.add(volumeId);
            return 1;
        }
        return Math.max(0, 1 - (now - started) / 4000f);
    }

    static void viewed(String bookId) {
        // 翻开过这本书、动画也播完的新卷不再标「新」。
        catalog().books().stream().filter(book -> book.id().equals(bookId)).flatMap(book -> book.volumes().stream())
                .map(CodexCatalog.Volume::id).filter(id -> glow(id) == 0 && SEEN_PLAYING.remove(id))
                .forEach(FRESH::remove);
    }

    static Optional<String> latestFreshBook() {
        // 打开功法谱时直接翻到刚添新卷的那本书。
        String last = null;
        for (String id : FRESH.keySet()) {
            last = id;
        }
        return Optional.ofNullable(last).flatMap(id -> catalog().bookOf(id)).map(CodexCatalog.Book::id);
    }
}
