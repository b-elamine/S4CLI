package sam4c.light.registry;

import sam4c.light.metamodel.MAttribute;
import sam4c.light.metamodel.MReference;
import sam4c.light.metamodel.Sam4cMetamodel;
import sam4c.light.model.Component;
import sam4c.light.model.Port;

import java.util.*;

// Turns a raw YAML component block into a Component. Fully metamodel-driven: which
// fields a type reads comes from ArchMetamodel.java (allAttributes/allReferences),
// not a hardcoded list here. Add a field to the metamodel and this loader picks it
// up with no other change -- add a whole new concrete type the same way, no Java
// handler class needed.
public class ComponentRegistry {

    public static ComponentRegistry withDefaults() {
        return new ComponentRegistry();
    }

    @SuppressWarnings("unchecked")
    public Component load(Map<String, Object> yaml) {
        String name = (String) yaml.get("name");
        String type = (String) yaml.getOrDefault("type", "App");

        var mm = Sam4cMetamodel.INSTANCE;
        if (mm.find(type).isEmpty() || mm.find(type).get().abstractClass() || !mm.isA(type, "Component"))
            throw new IllegalArgumentException(
                    "Unknown component type '" + type + "'. Known: " + knownConcreteTypes());

        List<Port> ports = List.of();
        List<Component> members = List.of();
        for (MReference r : mm.allReferences(type)) {
            if (r.containment() && r.targetClass().equals("Port")) ports = loadPorts(yaml);
            else if (r.containment() && mm.isA(r.targetClass(), "Component")) members = loadMembers(yaml, r.name());
        }

        return new Component(name, type, ports, members, loadAttributes(yaml), loadProperties(yaml));
    }

    private List<Component> loadMembers(Map<String, Object> yaml, String refName) {
        List<Component> members = new ArrayList<>();
        Object raw = yaml.get(refName);
        if (raw instanceof List<?> list) {
            for (Object item : list)
                if (item instanceof Map<?, ?> memberYaml)
                    members.add(load((Map<String, Object>) memberYaml));
        }
        return members;
    }

    private static List<String> knownConcreteTypes() {
        return Sam4cMetamodel.ARCH.concreteClasses().stream()
                .filter(c -> Sam4cMetamodel.INSTANCE.isA(c.name(), "Component"))
                .map(sam4c.light.metamodel.MClass::name).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    public static List<Port> loadPorts(Map<String, Object> yaml) {
        Object raw = yaml.get("ports");
        if (raw == null) return List.of();
        List<Port> ports = new ArrayList<>();
        for (Object p : (List<?>) raw) {
            if (p instanceof Map<?, ?> m) {
                // rich form: { name: http_in, number: 8080, protocol: http }
                Map<String, Object> pm = (Map<String, Object>) m;
                String pname = String.valueOf(pm.get("name"));
                Integer number = pm.get("number") instanceof Number n ? n.intValue() : null;
                String protocol = pm.get("protocol") != null ? pm.get("protocol").toString() : null;
                ports.add(new Port(pname, number, protocol));
            } else {
                // simple form: just the name
                ports.add(new Port(p.toString()));
            }
        }
        return ports;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> loadAttributes(Map<String, Object> yaml) {
        Object raw = yaml.get("attributes");
        if (raw == null) return Map.of();
        Map<String, Object> rawMap = (Map<String, Object>) raw;
        Map<String, String> result = new LinkedHashMap<>();
        rawMap.forEach((k, v) -> result.put(k, v.toString()));
        return result;
    }

    public static boolean bool(Map<String, Object> yaml, String key) {
        Object v = yaml.get(key);
        return v instanceof Boolean b && b;
    }

    // Reads every attribute + non-containment reference the metamodel declares for this
    // type into one properties map. Shared by load() and by DiagramReader (which builds
    // Components directly, not through load()).
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadProperties(Map<String, Object> yaml) {
        String type = (String) yaml.getOrDefault("type", "App");
        var mm = Sam4cMetamodel.INSTANCE;
        Map<String, Object> props = new LinkedHashMap<>();
        if (mm.find(type).isEmpty()) return props;

        for (MReference r : mm.allReferences(type)) {
            if (!r.containment()) {
                Object v = yaml.get(r.name());
                if (v != null) props.put(r.name(), v.toString());
            }
        }
        for (MAttribute a : mm.allAttributes(type)) {
            if (a.name().equals("type") || a.name().equals("name")) continue;
            Object v = yaml.get(a.name());
            if (v == null) continue;
            switch (a.type()) {
                case MAP     -> { if (v instanceof Map<?, ?> m) props.put(a.name(), m); }
                case LIST    -> { if (v instanceof List<?> l) props.put(a.name(), l); }
                case BOOLEAN, INT -> props.put(a.name(), v);
                case STRING  -> props.put(a.name(), v.toString());
            }
        }
        return props;
    }
}
