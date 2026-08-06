package sam4c.light.output;

import sam4c.light.model.*;
import sam4c.light.model.rule.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds Cytoscape graph data from the in-memory UnifiedModel.
 *
 * This is the single source of truth for the graph -- both the static HTML file
 * (HtmlReportGenerator) and the live web server build from here, so they render
 * identically.
 *
 * It reads only the real model objects (rule.sctxComponents(), component.name(),
 * etc.) -- never the JSON. Node identity uses an IdentityHashMap (== keying), so
 * one Java Component object maps to exactly one graph node: shared components
 * become shared nodes with multiple edges, mirroring the in-memory object graph.
 */
public final class GraphBuilder {

    private GraphBuilder() {}

    public static GraphData build(UnifiedModel model) {
        IdentityHashMap<Component, String> nodeIds = new IdentityHashMap<>();
        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();

        // Components (Colocation as a compound parent around its members)
        registerComponents(model.architecture().components(), null, nodeIds, nodes);

        Map<String, String> compNameToNodeId = new LinkedHashMap<>();
        nodeIds.forEach((c, id) -> compNameToNodeId.put(c.name(), id));

        // Connectors (architecture layer)
        Map<String, String> connectorNodeIds = new LinkedHashMap<>();
        for (Connector c : model.architecture().connectors()) {
            String id = "conn_" + c.name().replaceAll("[^a-zA-Z0-9]", "_");
            connectorNodeIds.put(c.name(), id);
            String label = c.name() + (c.external() ? "\n(external)" : "");
            nodes.add(String.format(
                "{ \"data\": { \"id\":\"%s\", \"label\":\"%s\", \"type\":\"Connector\", " +
                "\"attrs\":\"\", \"ports\":\"\", \"bg\":\"%s\", \"shape\":\"diamond\" } }",
                id, escape(label), c.external() ? "#546e7a" : "#78909c"
            ));
        }

        // Architecture links: component <-> connector topology, with flow direction.
        // OUT: component -> connector (arrow on connector end)
        // IN:  connector -> component (edge drawn reversed so the arrow points at the component)
        // INOUT: arrows on both ends
        int archSeq = 0;
        for (Link link : model.architecture().links()) {
            String compName = link.portRef().contains(".")
                ? link.portRef().substring(0, link.portRef().indexOf('.'))
                : link.portRef();
            String compNodeId = compNameToNodeId.get(compName);
            String connNodeId = connectorNodeIds.get(link.connectorName());
            if (compNodeId == null || connNodeId == null) continue;

            String dir = link.direction().token(); // in | out | inout
            // Label the arch edge with the port it attaches through (the part after the dot)
            String port = link.portRef().contains(".")
                ? link.portRef().substring(link.portRef().indexOf('.') + 1)
                : link.portRef();
            // Draw IN edges connector->component so the single arrow points at the component;
            // OUT and INOUT are drawn component->connector. The "dir" field drives arrowheads.
            String source = dir.equals("in") ? connNodeId : compNodeId;
            String target = dir.equals("in") ? compNodeId : connNodeId;
            edges.add(String.format(
                "{ \"data\": { \"id\":\"arch%d\", \"source\":\"%s\", \"target\":\"%s\", " +
                "\"label\":\"%s\", \"color\":\"#455a64\", \"style\":\"solid\", \"layer\":\"arch\", \"dir\":\"%s\" } }",
                archSeq++, source, target, escape(port), dir
            ));
        }

        // deployedOn: a Deployable's placement on a Host is a reference, not containment,
        // so it draws no nesting -- without this edge it would be invisible in the graph.
        // Dashed/grey, matching the same convention the paper figure uses for this relation.
        int placeSeq = 0;
        for (Component c : nodeIds.keySet()) {
            Object target = c.properties().get("deployedOn");
            if (target == null) continue;
            String hostNodeId = compNameToNodeId.get(target.toString());
            if (hostNodeId == null) continue;
            edges.add(String.format(
                "{ \"data\": { \"id\":\"place%d\", \"source\":\"%s\", \"target\":\"%s\", " +
                "\"label\":\"deployedOn\", \"color\":\"#999999\", \"style\":\"dashed\", \"layer\":\"arch\", \"dir\":\"out\" } }",
                placeSeq++, nodeIds.get(c), hostNodeId
            ));
        }

        // Security edges: one per resolved (sctx, tctx) leaf pair. No cross-rule
        // dedup -- two different rules may land on the same pair and both should show.
        int edgeSeq = 0;
        for (ResolvedRule rr : model.resolvedRules()) {
            String ruleType = rr.rule().getClass().getSimpleName();
            String color = edgeColor(rr.rule());
            String style = edgeStyle(rr.rule());
            String sctxAll = names(rr.sctxComponents());
            String tctxAll = names(rr.tctxComponents());
            String actxAll = names(rr.actxComponents());

            for (Component from : rr.sctxComponents()) {
                if (isContainer(from)) continue;
                for (Component to : rr.tctxComponents()) {
                    if (isContainer(to) || from == to) continue;
                    String fromId = nodeIds.get(from);
                    String toId = nodeIds.get(to);
                    if (fromId == null || toId == null) continue;
                    edges.add(String.format(
                        "{ \"data\": { \"id\":\"e%d\", \"source\":\"%s\", \"target\":\"%s\", " +
                        "\"label\":\"%s\", \"color\":\"%s\", \"style\":\"%s\", \"layer\":\"rule\", " +
                        "\"sctxAll\":\"%s\", \"tctxAll\":\"%s\", \"actxAll\":\"%s\" } }",
                        edgeSeq++, fromId, toId, ruleType, color, style,
                        escape(sctxAll), escape(tctxAll), escape(actxAll)
                    ));
                }
            }
        }

        // Coverage as a JSON object: { "ctx": ["Comp", ...], ... }
        StringBuilder cov = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<Component>> e : model.coverage().entrySet()) {
            if (!first) cov.append(", ");
            first = false;
            String comps = e.getValue().stream()
                .map(c -> "\"" + escape(c.name()) + "\"")
                .collect(Collectors.joining(", "));
            cov.append("\"").append(escape(e.getKey())).append("\": [").append(comps).append("]");
        }
        cov.append("}");

        return new GraphData(String.join(",\n", nodes), String.join(",\n", edges), cov.toString());
    }

    private static void registerComponents(List<Component> components, String parentId,
                                            IdentityHashMap<Component, String> nodeIds,
                                            List<String> nodes) {
        for (Component c : components) {
            String id = "n" + nodeIds.size();
            nodeIds.put(c, id); // == keying

            String parent = parentId != null ? String.format(", \"parent\":\"%s\"", parentId) : "";
            String attrsStr = escape(c.attributes().toString());
            String portsStr = escape(c.ports().stream().map(Port::name).collect(Collectors.joining(", ")));

            // Show a replica badge (e.g. "Api  x3") when the component declares scale.replicas
            String label = c.name() + replicaBadge(c);

            nodes.add(String.format(
                "{ \"data\": { \"id\":\"%s\", \"label\":\"%s\", \"type\":\"%s\", " +
                "\"attrs\":\"%s\", \"ports\":\"%s\", \"bg\":\"%s\", \"shape\":\"%s\"%s } }",
                id, escape(label), escape(c.type()),
                attrsStr, portsStr, nodeColor(c.type()), nodeShape(c.type()), parent
            ));

            if (!c.members().isEmpty())
                registerComponents(c.members(), id, nodeIds, nodes);
        }
    }

    /**
     * "  x3" when the component declares scale.replicas, with " - zone" / " - host"
     * appended when it also declares a spread (so the diagram shows copies fan out).
     */
    private static String replicaBadge(Component c) {
        Object scale = c.properties().get("scale");
        if (!(scale instanceof java.util.Map<?, ?> m) || m.get("replicas") == null) return "";
        String badge = "  x" + m.get("replicas");
        Object spread = c.properties().get("spread");
        if (spread != null && !"none".equalsIgnoreCase(spread.toString()))
            badge += " - " + spread;   // e.g. "x3 - zone"
        return badge;
    }

    // Hosts are never rule endpoints, and Colocation is a compound parent, not a leaf.
    // Security edges connect leaf Deployables, so both are skipped as edge endpoints.
    private static final java.util.Set<String> CONTAINER_TYPES = java.util.Set.of(
        "VM", "PM", "Worker", "Colocation");

    private static boolean isContainer(Component c) {
        return !c.members().isEmpty() || CONTAINER_TYPES.contains(c.type());
    }

    private static String nodeColor(String type) {
        return switch (type) {
            case "VM", "PM", "Worker" -> "#e8eaf6";  // hosts: lavender
            case "Colocation" -> "#fef9c3";                       // co-location: amber-50
            case "App"  -> "#1565c0";
            case "Data" -> "#e65100";
            default     -> "#37474f";
        };
    }

    private static String nodeShape(String type) {
        return switch (type) {
            case "App"  -> "ellipse";
            case "Data" -> "barrel";
            // hosts + Colocation are rounded rectangles (compound containers)
            case "VM", "PM", "Worker", "Colocation" -> "roundrectangle";
            default     -> "diamond";
        };
    }

    private static String edgeColor(SecurityRule rule) {
        return switch (rule) {
            case Confidentiality ignored -> "#1565c0";
            case Integrity       ignored -> "#2e7d32";
            case Isolation       ignored -> "#c62828";
            case Authentication  ignored -> "#6a1b9a";
            case Authorization   ignored -> "#0891b2";
            case Availability    ignored -> "#00897b";   // node-level: no edge drawn, kept for exhaustiveness
        };
    }

    private static String edgeStyle(SecurityRule rule) {
        return rule instanceof Isolation ? "dashed" : "solid";
    }

    /** JSON string escaping (output is valid JSON, which is also valid JS). */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String names(List<Component> comps) {
        return comps.stream().map(Component::name).collect(Collectors.joining(", "));
    }
}
