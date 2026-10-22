package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;

/** 所有页面复用同一份快照统计；在途属于独立账户，预约不能再次算作库存。 */
public final class QiDisplay {
    private QiDisplay() {
        // 只派生展示值，不创建可花费账户。
    }

    public static double amount(QiInventory inventory) {
        // 元数仍可用于总账和吞吐展示；容量与满载比例必须读取 volume。
        return inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
    }

    public static double volume(QiInventory inventory) {
        // 混相明细分别换算空间占用，不能把不同密度的数量直接相加当作容量。
        return QiDensity.volume(inventory);
    }

    public static double storageFraction(QiInventory inventory, double capacityVolume) {
        // 经脉占用比例使用实际体积，避免高密度真元按元数显示成错误满载。
        return ratio(volume(inventory), capacityVolume);
    }

    public static double displayedCapacity(double volume, QiInventory inventory) {
        // 空账户使用游离态基准，混相使用当前明细的平均密度换算用户可读元数。
        return QiDensity.displayedCapacity(volume, inventory);
    }

    public static double lumen(BodySnapshot body) {
        // 元数是资源总账；容量门槛使用单独的 lumenVolume 派生值。
        return body.getMeridianStatesList().stream().mapToDouble(state -> amount(state.getLumenQi())).sum();
    }

    public static double lumenVolume(BodySnapshot body) {
        // 开脉准备与局部满载条读取真实空间占用。
        return body.getMeridianStatesList().stream().mapToDouble(state -> volume(state.getLumenQi())).sum();
    }

    public static double total(BodySnapshot body) {
        // 加工输入已离开缓存，在途已离开发出端，每笔只在当前所有者计量。
        double total = body.getMeridianStatesList().stream()
                .mapToDouble(state -> amount(state.getLumenQi()) + amount(state.getWallQi())).sum();
        for (OrganState organ : body.getOrganStatesList()) {
            total += amount(organ.getBuffer());
            if (organ.hasProcess()) {
                total += amount(organ.getProcess().getInput());
            }
        }
        return total + body.getTransitsList().stream()
                .mapToDouble(transit -> amount(transit.getCargo())).sum();
    }

    public static double incoming(BodySnapshot body, String meridianId) {
        // 预约只用于虚线条，不增加已到账长度；阻塞货物同样保有目的空间。
        MeridianDefinition definition = body.getStructure().getMeridiansList().stream()
                .filter(item -> item.getId().equals(meridianId)).findFirst().orElse(null);
        if (definition == null) {
            return 0;
        }
        var ports = definition.getPortsList().stream().map(Port::getId).toList();
        var routes = body.getStructure().getConnectionsList().stream()
                .filter(route -> ports.contains(route.getTo())).map(Connection::getId).toList();
        return body.getTransitsList().stream().filter(transit -> routes.contains(transit.getConnectionId()))
                .mapToDouble(transit -> volume(transit.getCargo())).sum();
    }

    public static double ratio(double amount, double capacity) {
        // 缺少分母显示空条，所有来自宿主的进度都限制在绘制范围。
        return capacity > 0 ? Math.max(0, Math.min(1, amount / capacity)) : 0;
    }
}
