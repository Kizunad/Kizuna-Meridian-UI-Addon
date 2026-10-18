package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;

/** 所有页面复用同一份快照统计；在途属于独立账户，预约不能再次算作库存。 */
public final class QiDisplay {
    private QiDisplay() {
        // 只派生展示值，不创建可花费账户。
    }

    public static double amount(QiInventory inventory) {
        // 保留相态和属性原始明细，总览仅求和。
        return inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
    }

    public static double lumen(BodySnapshot body) {
        // 开脉准备与局部储量都使用实际到达内腔的真元。
        return body.getMeridianStatesList().stream().mapToDouble(state -> amount(state.getLumenQi())).sum();
    }

    public static double total(BodySnapshot body) {
        // 加工输入已离开缓存，在途已离开发出端，每笔只在当前所有者计量。
        double total = lumen(body);
        total += body.getMeridianStatesList().stream().mapToDouble(state -> amount(state.getWallQi())).sum();
        for (OrganState organ : body.getOrganStatesList()) {
            total += amount(organ.getBuffer());
            if (organ.hasProcess()) {
                total += amount(organ.getProcess().getRecipe().getInput());
            }
        }
        return total + body.getTransitsList().stream().mapToDouble(transit -> amount(transit.getCargo())).sum();
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
                .mapToDouble(transit -> amount(transit.getCargo())).sum();
    }

    public static double ratio(double amount, double capacity) {
        // 缺少分母显示空条，所有来自宿主的进度都限制在绘制范围。
        return capacity > 0 ? Math.max(0, Math.min(1, amount / capacity)) : 0;
    }
}
