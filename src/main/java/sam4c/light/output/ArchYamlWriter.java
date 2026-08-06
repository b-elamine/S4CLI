package sam4c.light.output;

import sam4c.light.model.Architecture;
import sam4c.light.model.Component;
import sam4c.light.model.Connector;
import sam4c.light.model.Implementation;
import sam4c.light.model.Link;
import sam4c.light.model.Port;

import java.util.Map;

// Writes an Architecture back out as .arch.yaml (inverse of the loader). Used by the
// Studio's "Download YAML" so a drawing round-trips to the format the CLI reads.
public final class ArchYamlWriter {

    private ArchYamlWriter() {}

    public static String write(Architecture arch) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(arch.name()).append("\n\n");

        sb.append("components:\n");
        for (Component c : arch.components()) writeComponent(sb, c, 1);
        sb.append("\n");

        sb.append("connectors:\n");
        if (arch.connectors().isEmpty()) sb.append("  []\n");
        for (Connector c : arch.connectors()) {
            sb.append("  - name: ").append(c.name()).append("\n");
            if (c.external())         sb.append("    external: true\n");
            if (c.protocol() != null) sb.append("    protocol: ").append(c.protocol()).append("\n");
        }
        sb.append("\n");

        sb.append("links:\n");
        if (arch.links().isEmpty()) sb.append("  []\n");
        for (Link l : arch.links()) {
            sb.append("  - port: ").append(l.portRef()).append("\n");
            sb.append("    connector: ").append(l.connectorName()).append("\n");
            if (l.direction() != sam4c.light.model.Direction.INOUT)
                sb.append("    direction: ").append(l.direction().token()).append("\n");
        }
        sb.append("\n");

        sb.append("implementations:\n");
        if (arch.implementations().isEmpty()) sb.append("  []\n");
        for (Implementation impl : arch.implementations()) {
            sb.append("  - name: ").append(impl.name()).append("\n");
            if (impl.runtime() != null)  sb.append("    runtime: ").append(impl.runtime()).append("\n");
            if (impl.image() != null)    sb.append("    image: ").append(impl.image()).append("\n");
            if (impl.resources() != null && !impl.resources().isEmpty()) {
                sb.append("    resources:\n");
                for (Map.Entry<String, Object> e : impl.resources().entrySet())
                    sb.append("      ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            if (impl.config() != null && !impl.config().isEmpty()) {
                sb.append("    config:\n");
                for (Map.Entry<String, Object> e : impl.config().entrySet())
                    sb.append("      ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    private static void writeComponent(StringBuilder sb, Component c, int depth) {
        String pad = "  ".repeat(depth);
        sb.append(pad).append("- name: ").append(c.name()).append("\n");
        sb.append(pad).append("  type: ").append(c.type()).append("\n");

        if (!c.attributes().isEmpty()) {
            sb.append(pad).append("  attributes:\n");
            for (Map.Entry<String, String> e : c.attributes().entrySet())
                sb.append(pad).append("    ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }

        if (!c.ports().isEmpty()) {
            boolean anyRich = c.ports().stream().anyMatch(p -> p.number() != null || p.protocol() != null);
            if (!anyRich) {
                // simple form: ports: [a, b]
                String ports = String.join(", ", c.ports().stream().map(Port::name).toList());
                sb.append(pad).append("  ports: [").append(ports).append("]\n");
            } else {
                // rich form: ports:\n  - { name: a, number: 80, protocol: http }
                sb.append(pad).append("  ports:\n");
                for (Port p : c.ports()) {
                    sb.append(pad).append("    - { name: ").append(p.name());
                    if (p.number() != null)   sb.append(", number: ").append(p.number());
                    if (p.protocol() != null) sb.append(", protocol: ").append(p.protocol());
                    sb.append(" }\n");
                }
            }
        }

        // walk the type's metamodel attributes so any declared field gets written,
        // no hand-maintained list to keep in sync
        writeScalar(sb, pad, "deployedOn", c.properties().get("deployedOn"));   // a reference, not an attribute
        writeScalar(sb, pad, "implementation", c.properties().get("implementation"));   // a reference, not an attribute
        for (sam4c.light.metamodel.MAttribute a : sam4c.light.metamodel.Sam4cMetamodel.INSTANCE.allAttributes(c.type())) {
            String key = a.name();
            if (key.equals("type") || key.equals("name")) continue;  // handled above
            Object v = c.properties().get(key);
            if (v == null) continue;
            switch (a.type()) {
                case MAP  -> writeNestedMap(sb, pad, key, v);
                case LIST -> writeList(sb, pad, key, v);
                default   -> writeScalar(sb, pad, key, v);
            }
        }

        if (!c.members().isEmpty()) {
            sb.append(pad).append("  members:\n");
            for (Component member : c.members()) writeComponent(sb, member, depth + 2);
        }
    }

    /** Writes `key: value` if value is non-null. */
    private static void writeScalar(StringBuilder sb, String pad, String key, Object value) {
        if (value != null) sb.append(pad).append("  ").append(key).append(": ").append(value).append("\n");
    }

    /** Writes `key: [a, b]` if value is a non-empty list. */
    private static void writeList(StringBuilder sb, String pad, String key, Object value) {
        if (!(value instanceof java.util.List<?> l) || l.isEmpty()) return;
        sb.append(pad).append("  ").append(key).append(": [")
          .append(String.join(", ", l.stream().map(String::valueOf).toList())).append("]\n");
    }

    /** Writes a one-level nested map (e.g. scale, resources) under `key`, if present. */
    private static void writeNestedMap(StringBuilder sb, String pad, String key, Object value) {
        if (!(value instanceof Map<?, ?> m) || m.isEmpty()) return;
        sb.append(pad).append("  ").append(key).append(":\n");
        for (Map.Entry<?, ?> e : m.entrySet())
            sb.append(pad).append("    ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
    }
}
