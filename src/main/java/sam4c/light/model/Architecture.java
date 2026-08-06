package sam4c.light.model;

import java.util.List;

public record Architecture(
        String name,
        List<Component> components,
        List<Connector> connectors,
        List<Link> links,
        List<Implementation> implementations
) {}
