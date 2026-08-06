package sam4c.light.web;

import sam4c.light.model.Architecture;
import sam4c.light.model.Component;
import sam4c.light.model.Connector;
import sam4c.light.model.Direction;
import sam4c.light.model.Link;
import sam4c.light.model.Port;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an Architecture from a diagram posted by the web canvas.
 *
 * The inverse of GraphBuilder (which goes Architecture -> graph). The diagram is a
 * plain domain structure the canvas serializes to:
 *
 *   {
 *     "name": "model",
 *     "components": [ { "id","name","type","parent","attributes":{}, "ports":[] } ],
 *     "connectors": [ { "name","external" } ],
 *     "links":      [ { "port":"Comp.port", "connector":"ConnName" } ]
 *   }
 *
 * Components are reassembled into the nesting tree by their parent id.
 */
public final class DiagramReader {

    private DiagramReader() {}

    @SuppressWarnings("unchecked")
    public static Architecture build(Map<String, Object> diagram) {
        String name = (String) diagram.getOrDefault("name", "model");

        List<Map<String, Object>> rawComps =
            (List<Map<String, Object>>) diagram.getOrDefault("components", List.of());

        // Group child raw-DTOs by parent id (null parent = top level)
        Map<String, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
        for (Map<String, Object> rc : rawComps) {
            Object p = rc.get("parent");
            String parent = (p == null) ? "" : p.toString();
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(rc);
        }

        List<Component> roots = buildChildren("", byParent);

        // Connectors
        List<Connector> connectors = new ArrayList<>();
        for (Map<String, Object> rc : (List<Map<String, Object>>) diagram.getOrDefault("connectors", List.of())) {
            String protocol = rc.get("protocol") != null ? rc.get("protocol").toString() : null;
            connectors.add(new Connector((String) rc.get("name"), bool(rc.get("external")), protocol));
        }

        // Links
        List<Link> links = new ArrayList<>();
        for (Map<String, Object> rl : (List<Map<String, Object>>) diagram.getOrDefault("links", List.of())) {
            Direction dir = Direction.parse((String) rl.get("direction"));
            links.add(new Link((String) rl.get("port"), (String) rl.get("connector"), dir));
        }

        // The canvas has no way to author Implementations yet -- always empty for now.
        return new Architecture(name, roots, connectors, links, List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Component> buildChildren(String parentId,
                                                 Map<String, List<Map<String, Object>>> byParent) {
        List<Component> result = new ArrayList<>();
        for (Map<String, Object> rc : byParent.getOrDefault(parentId, List.of())) {
            String id = String.valueOf(rc.get("id"));
            String cname = (String) rc.getOrDefault("name", id);
            String type = (String) rc.getOrDefault("type", "App");

            Map<String, String> attrs = new LinkedHashMap<>();
            Object rawAttrs = rc.get("attributes");
            if (rawAttrs instanceof Map<?, ?> m)
                m.forEach((k, v) -> { if (v != null) attrs.put(k.toString(), v.toString()); });

            // reuse the loader so simple ("p") and rich ({name,number,protocol}) forms both work
            List<Port> ports = sam4c.light.registry.ComponentRegistry.loadPorts(rc);

            List<Component> members = buildChildren(id, byParent);

            // Deployment properties -- reuse the loader so the editor and CLI stay in sync
            Map<String, Object> props = sam4c.light.registry.ComponentRegistry.loadProperties(rc);

            result.add(new Component(cname, type, ports, members, attrs, props));
        }
        return result;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(o));
    }
}
