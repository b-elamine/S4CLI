package sam4c.light.loader;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import sam4c.light.model.Architecture;
import sam4c.light.model.Component;
import sam4c.light.model.Connector;
import sam4c.light.model.Direction;
import sam4c.light.model.Implementation;
import sam4c.light.model.Link;
import sam4c.light.registry.ComponentRegistry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ArchLoader {

    private final ComponentRegistry registry;

    public ArchLoader(ComponentRegistry registry) {
        this.registry = registry;
    }

    /** Load from a file. */
    @SuppressWarnings("unchecked")
    public Architecture load(File file) throws IOException {
        Map<String, Object> root = new YAMLMapper().readValue(file, Map.class);
        return build(root, file.getName());
    }

    /** Load from raw YAML content (used by the web server, which has no file). */
    @SuppressWarnings("unchecked")
    public Architecture load(String yaml, String fallbackName) throws IOException {
        Map<String, Object> root = new YAMLMapper().readValue(yaml, Map.class);
        return build(root, fallbackName);
    }

    @SuppressWarnings("unchecked")
    private Architecture build(Map<String, Object> root, String fallbackName) throws IOException {
        String name = (String) root.getOrDefault("name", fallbackName);

        List<Component> components = new ArrayList<>();
        Object rawComponents = root.get("components");
        if (rawComponents instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> yaml) {
                    components.add(registry.load((Map<String, Object>) yaml));
                }
            }
        }

        List<Connector> connectors = new ArrayList<>();
        Object rawConnectors = root.get("connectors");
        if (rawConnectors instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> yaml) {
                    Map<String, Object> m = (Map<String, Object>) yaml;
                    String cname = (String) m.get("name");
                    boolean external = ComponentRegistry.bool(m, "external");
                    String protocol = m.get("protocol") != null ? m.get("protocol").toString() : null;
                    connectors.add(new Connector(cname, external, protocol));
                }
            }
        }

        List<Link> links = new ArrayList<>();
        Object rawLinks = root.get("links");
        if (rawLinks instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> yaml) {
                    Map<String, Object> m = (Map<String, Object>) yaml;
                    String port = (String) m.get("port");
                    String connector = (String) m.get("connector");
                    if (port == null || connector == null)
                        throw new IOException("Each link requires 'port' and 'connector' fields");
                    Direction dir;
                    try {
                        dir = Direction.parse((String) m.get("direction"));
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Link " + port + " -> " + connector + ": " + e.getMessage());
                    }
                    links.add(new Link(port, connector, dir));
                }
            }
        }

        List<Implementation> implementations = new ArrayList<>();
        Object rawImplementations = root.get("implementations");
        if (rawImplementations instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> yaml) {
                    Map<String, Object> m = (Map<String, Object>) yaml;
                    String iname = (String) m.get("name");
                    String runtime = m.get("runtime") != null ? m.get("runtime").toString() : null;
                    String image = m.get("image") != null ? m.get("image").toString() : null;
                    Map<String, Object> resources = m.get("resources") instanceof Map<?, ?> r
                            ? (Map<String, Object>) r : Map.of();
                    Map<String, Object> config = m.get("config") instanceof Map<?, ?> c
                            ? (Map<String, Object>) c : Map.of();
                    implementations.add(new Implementation(iname, runtime, image, resources, config));
                }
            }
        }

        return new Architecture(name, components, connectors, links, implementations);
    }
}
