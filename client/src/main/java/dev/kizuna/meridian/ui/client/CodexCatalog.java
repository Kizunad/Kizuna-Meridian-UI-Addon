package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ScrollDrop;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog.NamedTechnique;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog.Scroll;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 功法谱的目录：功法库初始化期登记的每一卷，按书分组；新功法包装上就出现，不手写清单。 */
record CodexCatalog(List<Book> books) {
    CodexCatalog {
        // 目录在整局里不变，外部拿到的列表不可改。
        books = List.copyOf(books);
    }

    static CodexCatalog load() {
        // 两端同一份卷轴清单；同书集中，书按首次出现的顺序，书内保持卷的声明顺序。
        var grouped = new LinkedHashMap<String, List<Volume>>();
        var bookNames = new LinkedHashMap<String, String>();
        for (var item : TechniqueScrollItems.scrolls().values()) {
            Scroll scroll = item.scroll();
            String bookId = scroll.volume().getBookId();
            bookNames.putIfAbsent(bookId, scroll.bookName());
            grouped.computeIfAbsent(bookId, ignored -> new ArrayList<>()).add(Volume.of(scroll));
        }
        var books = new ArrayList<Book>();
        grouped.forEach((id, volumes) -> books.add(new Book(id, bookNames.get(id), volumes)));
        return new CodexCatalog(books);
    }

    int volumeCount() {
        // 全谱完成度的分母随装上的功法包变化。
        return books.stream().mapToInt(book -> book.volumes().size()).sum();
    }

    Optional<Volume> volume(String id) {
        // 学会提示按卷身份找回所在的书和卷名。
        return books.stream().flatMap(book -> book.volumes().stream())
                .filter(volume -> volume.id().equals(id)).findFirst();
    }

    Optional<Book> bookOf(String volumeId) {
        // 点亮提示要写本书的进度。
        return books.stream().filter(book -> book.volumes().stream()
                .anyMatch(volume -> volume.id().equals(volumeId))).findFirst();
    }

    record Book(String id, String name, List<Volume> volumes) {
        // 一本书：身份、书名、卷序。
        Book {
            // 书内卷序固定为目录声明顺序。
            volumes = List.copyOf(volumes);
        }
    }

    record Volume(String id, String name, String fullName, Identifier itemId, List<NamedTechnique> techniques,
                  List<String> prerequisiteIds, Map<String, String> prerequisiteNames, List<ScrollDrop> drops) {
        // 一卷：图鉴要用的身份、名字、式、前置与掉落声明。
        static Volume of(Scroll scroll) {
            // 前置卷名由卷轴目录解析好，图鉴不重复解析其他清单。
            var volume = scroll.volume();
            var names = new LinkedHashMap<String, String>();
            for (int index = 0; index < volume.getPrerequisiteVolumeIdsCount(); index++) {
                names.put(volume.getPrerequisiteVolumeIds(index), scroll.prerequisites().get(index));
            }
            return new Volume(volume.getId(), scroll.name(), scroll.displayName(),
                    Identifier.parse(scroll.itemId()), scroll.techniques(), volume.getPrerequisiteVolumeIdsList(),
                    names, volume.getScrollDropsList());
        }

        Set<String> techniqueIds() {
            // 学会一卷 = 这卷每一式都在已学列表里，与卷轴物品的判定一致。
            return techniques.stream().map(named -> named.reference().getId()).collect(Collectors.toSet());
        }

        int minimumRealm() {
            // 一卷里最低的那一式决定这卷能不能开始练；未声明境界按游炁算。
            return techniques.stream().mapToInt(Volume::realmOf).min().orElse(1);
        }

        static int realmOf(NamedTechnique technique) {
            // 目录未声明境界的式视作游炁即可施展。
            return Math.max(1, technique.definition().getMinimumRealmValue());
        }

        ItemStack icon() {
            // 卷轴物品由功法库按卷登记，图鉴只拿来画图标。
            return new ItemStack(BuiltInRegistries.ITEM.getValue(itemId));
        }
    }
}
