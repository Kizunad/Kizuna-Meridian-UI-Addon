package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider.Point;
import java.util.*;
import org.joml.Vector3f;

/** 从真实在途和经脉网格提取展示轨迹；路径缓存随构型变化失效。 */
public final class MeridianFlow {
    private final Map<String, BodyVisual> visuals = new HashMap<>();
    private final BodyScene scene;
    private final MeridianRouteProvider routeProvider;
    private final Map<String, List<Vector3f>> routes = new HashMap<>();
    private final Map<String, Flight> flights = new LinkedHashMap<>();
    private final Set<String> lockedPorts = new HashSet<>();
    private BodyStructure structure;

    MeridianFlow(BodyScene scene, MeridianRouteProvider routeProvider) {
        // 模型绑定来自生物内容；不按左右、名称或人体经脉数量猜测路径。
        this.scene = scene;
        this.routeProvider = Objects.requireNonNull(routeProvider, UiErrors.REQUIRED_ROUTE_PROVIDER);
        for (BodyVisual node : scene.presentation().getVisualsList()) {
            if (node.getTargetKind() == BodyTargetKind.MERIDIAN || node.getTargetKind() == BodyTargetKind.ORGAN) {
                visuals.putIfAbsent(node.getTargetKind().name() + ":" + node.getTargetId(), node);
            }
        }
    }

    void update(BodySnapshot body) {
        // 普通摄入扩散与开脉都显示真实运输；没有运输时自然停止。
        if (!body.getStructure().equals(structure)) {
            structure = body.getStructure();
            routes.clear();
            flights.clear();
        }
        // 隐藏层级的自然扩散仍由核心执行；光迹不能泄露未解锁经脉的几何。
        lockedPorts.clear();
        for (MeridianDefinition meridian : structure.getMeridiansList()) {
            BodyVisual visual = visuals.get("MERIDIAN:" + meridian.getId());
            if (visual != null && !BodyScene.realmAllows(body, visual)) {
                meridian.getPortsList().stream().map(Port::getId).forEach(lockedPorts::add);
            }
        }
        Set<String> hiddenConnections = new HashSet<>();
        for (Connection connection : structure.getConnectionsList()) {
            if (lockedPorts.contains(connection.getFrom()) || lockedPorts.contains(connection.getTo())) {
                hiddenConnections.add(connection.getId());
            }
        }
        Set<String> current = new HashSet<>();
        for (QiTransit transit : body.getTransitsList()) {
            if (transit.hasCircuit()) {
                // 受控闭环按权威工作进度沿整条真实路线显示，不按单段到达时间猜测位置。
                var path = new ArrayList<Vector3f>();
                for (String routeId : transit.getCircuit().getConnectionIdsList()) {
                    if (hiddenConnections.contains(routeId)) {
                        path.clear();
                        break;
                    }
                    path.addAll(routes.computeIfAbsent(routeId, this::route));
                }
                if (path.size() >= 2) {
                    current.add(transit.getId());
                    double progress = transit.getCircuit().getWorkMillis() / transit.getCircuit().getDurationMillis();
                    flights.put(transit.getId(), new Flight(path, System.nanoTime(), 1, progress));
                }
                continue;
            }
            boolean freeQi = transit.getCargo().getEntriesList().stream()
                    .anyMatch(qi -> QiDensity.phase(qi.getDensity()) == QiPhase.FREE);
            if (transit.getStatus() != TransitStatus.MOVING || !freeQi || current.size() >= 96
                    || hiddenConnections.contains(transit.getConnectionId())) {
                continue;
            }
            List<Vector3f> path = routes.computeIfAbsent(transit.getConnectionId(), this::route);
            if (path.size() < 2) {
                continue;
            }
            current.add(transit.getId());
            flights.computeIfAbsent(transit.getId(), id -> new Flight(path, System.nanoTime(),
                    Math.max(50, transit.getArrivalMillis() - body.getSettledAt().getElapsedMillis())));
        }
        flights.keySet().retainAll(current);
    }

    Collection<Flight> flights() {
        // 渲染器只读取有限数量的轨迹，不拥有任何真元余额。
        return flights.values();
    }

    private List<Vector3f> route(String connectionId) {
        // 运输方向来自权威连接端口；已存在的连接必须能绑定两端模型与路线。
        Connection connection = structure.getConnectionsList().stream()
                .filter(item -> item.getId().equals(connectionId)).findFirst().orElse(null);
        if (connection == null) {
            return List.of();
        }
        BodyVisual from = owner(connection.getFrom());
        BodyVisual to = owner(connection.getTo());
        if (from == null || to == null) {
            throw UiErrors.invalid(UiErrors.MISSING_CONNECTION_ENDPOINT);
        }
        // 客户端扩展只在真实连接与两端模型齐全后提供交汇点，不增加运输或改变耗时。
        List<Point> waypoints = waypoints(connection);
        Point first = waypoints.get(0);
        Vector3f sourceJunction = new Vector3f(first.x(), first.y(), first.z());
        List<Vector3f> incoming = branch(from, sourceJunction);
        Collections.reverse(incoming);
        for (Point point : waypoints) {
            incoming.add(new Vector3f(point.x(), point.y(), point.z()));
        }
        incoming.addAll(branch(to, incoming.get(incoming.size() - 1)));
        return List.copyOf(incoming);
    }

    private List<Point> waypoints(Connection connection) {
        // 先取得必需路线，再检查点数与模型边界；错误不能被默认网格路径掩盖。
        List<Point> points = routeProvider.waypoints(scene.presentation(), structure, connection);
        if (points == null || points.size() < 2 || points.size() > 256) {
            throw UiErrors.invalid(UiErrors.INVALID_CONNECTION_PATH);
        }
        ModelPoint minimum = scene.presentation().getMinimum();
        ModelPoint maximum = scene.presentation().getMaximum();
        for (Point point : points) {
            if (point == null || !Float.isFinite(point.x()) || !Float.isFinite(point.y()) || !Float.isFinite(point.z())
                    || point.x() < minimum.getX() || point.x() > maximum.getX()
                    || point.y() < minimum.getY() || point.y() > maximum.getY()
                    || point.z() < minimum.getZ() || point.z() > maximum.getZ()) {
                throw UiErrors.invalid(UiErrors.INVALID_CONNECTION_PATH);
            }
        }
        return List.copyOf(points);
    }

    private BodyVisual owner(String port) {
        // 器官出口到经脉同样需要显示，位置完全使用生物包几何。
        BodyVisual result = structure.getMeridiansList().stream()
                .filter(meridian -> meridian.getPortsList().stream().anyMatch(candidate -> candidate.getId().equals(port)))
                .map(meridian -> visuals.get("MERIDIAN:" + meridian.getId())).filter(Objects::nonNull).findFirst().orElse(null);
        if (result != null) {
            return result;
        }
        return structure.getOrgansList().stream()
                .filter(organ -> organ.getPortsList().stream().anyMatch(candidate -> candidate.getId().equals(port)))
                .map(organ -> visuals.get("ORGAN:" + organ.getId())).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public static List<Vector3f> branch(BodyVisual visual, Vector3f junction) {
        // 网格边构成有限图；从最接近交汇处的顶点向最远端取路径，保留曲折和缠绕。
        // UV 接缝会复制同位置顶点；先焊接空间坐标，避免轨迹被切成独立的三角面。
        Map<ModelPoint, Integer> welded = new LinkedHashMap<>();
        List<Vector3f> points = new ArrayList<>();
        int[] vertexIndices = new int[visual.getVerticesCount()];
        for (int index = 0; index < visual.getVerticesCount(); index++) {
            ModelPoint point = visual.getVertices(index);
            Integer weldedIndex = welded.get(point);
            if (weldedIndex == null) {
                weldedIndex = points.size();
                welded.put(point, weldedIndex);
                points.add(new Vector3f(point.getX(), point.getY(), point.getZ()));
            }
            vertexIndices[index] = weldedIndex;
        }
        int count = points.size();
        if (count == 0) {
            return new ArrayList<>();
        }
        List<Set<Integer>> edges = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            edges.add(new TreeSet<>());
        }
        for (int index = 0; index < visual.getTrianglesCount(); index += 3) {
            for (int corner = 0; corner < 3; corner++) {
                int a = vertexIndices[visual.getTriangles(index + corner)];
                int b = vertexIndices[visual.getTriangles(index + (corner + 1) % 3)];
                edges.get(a).add(b);
                edges.get(b).add(a);
            }
        }
        int start = 0;
        for (int index = 1; index < count; index++) {
            if (points.get(index).distanceSquared(junction) < points.get(start).distanceSquared(junction)) {
                start = index;
            }
        }
        int[] previous = new int[count];
        Arrays.fill(previous, -1);
        previous[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        int end = start;
        while (!queue.isEmpty()) {
            end = queue.removeFirst();
            for (int neighbor : edges.get(end)) {
                if (previous[neighbor] == -1) {
                    previous[neighbor] = end;
                    queue.add(neighbor);
                }
            }
        }
        List<Vector3f> path = new ArrayList<>();
        while (end != start) {
            path.add(points.get(end));
            end = previous[end];
        }
        path.add(points.get(start));
        Collections.reverse(path);
        return path;
    }

    static final class Flight {
        private final List<Vector3f> points;
        private final double[] lengths;
        private final long startedNanos;
        private final long durationMillis;
        private final double controlledProgress;

        Flight(List<Vector3f> points, long startedNanos, long durationMillis) {
            // 普通运输仍沿原到达时长插值。
            this(points, startedNanos, durationMillis, -1);
        }

        Flight(List<Vector3f> points, long startedNanos, long durationMillis, double controlledProgress) {
            // 弧长让光点匀速经过长短不同的网格边，速度只用于表现。
            this.points = points;
            this.startedNanos = startedNanos;
            this.durationMillis = durationMillis;
            this.controlledProgress = controlledProgress;
            lengths = new double[points.size()];
            for (int index = 1; index < points.size(); index++) {
                lengths[index] = lengths[index - 1] + points.get(index - 1).distance(points.get(index));
            }
        }

        Vector3f point(long now, double trail) {
            // 到达预期终点后等下一份权威快照，不循环制造第二份运输。
            double progress = Math.max(0, Math.min(1, (now - startedNanos) / (durationMillis * 1_000_000.0) - trail));
            if (controlledProgress >= 0) {
                progress = Math.max(0, controlledProgress - trail);
            }
            double distance = progress * lengths[lengths.length - 1];
            for (int index = 1; index < lengths.length; index++) {
                if (lengths[index] >= distance) {
                    double segment = lengths[index] - lengths[index - 1];
                    float fraction = segment > 0 ? (float) ((distance - lengths[index - 1]) / segment) : 0;
                    return new Vector3f(points.get(index - 1)).lerp(points.get(index), fraction);
                }
            }
            return new Vector3f(points.get(points.size() - 1));
        }
    }
}
