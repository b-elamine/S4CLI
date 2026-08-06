package sam4c.light.metamodel;

import static sam4c.light.metamodel.MDataType.*;

// The architecture metamodel. Components are either Deployables (App/Data, things
// that run), Hosts (VM/PM/Worker, what they run on), or a Colocation (a group of
// Deployables scaled and placed together). Wired together by ports, connectors and
// links. Components are ContextualElements, which is how security rules get to
// point at them.
public final class ArchMetamodel {

    public static final MPackage INSTANCE = define();

    private ArchMetamodel() {}

    private static MPackage define() {
        return new MPackage("architecture", "http://avalon.inria.fr/sam4c/architecture/",
                java.util.List.of(

            MClass.builder("Component").abstractClass()
                .superType("ContextualElement")
                .attr("type", STRING, 1, 1)
                .build(),

            // Unit: anything that scales and is spread as one thing, whether it runs
            // by itself (Deployable) or as a co-scheduled group (Colocation).
            MClass.builder("Unit").abstractClass().superType("Component")
                .attr("scale",  MAP,    0, 1)   // {replicas, min, max, metric}
                .attr("spread", STRING, 0, 1, "none", "host", "zone")   // distribute replicas across failure domains
                .build(),

            // Deployable: anything that runs. All the deployment fields live here so
            // App/Data inherit them, and the loader/conformance/Studio/writer all read
            // these declarations.
            MClass.builder("Deployable").abstractClass().superType("Unit")
                .attr("exposure",    STRING, 0, 1, "none", "internal", "external")
                .attr("lifecycle",   STRING, 0, 1, "continuous", "batch", "scheduled")
                .attr("credentials", LIST,   0, 1)   // [NAME] references
                .attr("placement",   STRING, 0, 1)   // the zone value
                .ref("deployedOn",     "Host",           false, 0, 1)   // placement (reference, not containment)
                .ref("implementation", "Implementation", false, 0, 1)   // the build it runs (reference, not containment)
                .ref("ports",          "Port",           true,  0, -1)
                .build(),

            MClass.builder("App") .superType("Deployable").build(),   // stateless
            MClass.builder("Data").superType("Deployable")            // stateful
                .attr("persistent", BOOLEAN, 0, 1)
                .attr("storage",    STRING,  0, 1)
                .build(),

            // Colocation: a group of Deployables scaled and placed together. The only
            // containment edge left between two components.
            MClass.builder("Colocation").superType("Unit")
                .ref("members", "Deployable", true, 0, -1)
                .build(),

            // Host: what Deployables run on.
            MClass.builder("Host").abstractClass().superType("Component")
                .attr("capacity", MAP,    0, 1)   // {cpu, memory} -- same shape as Implementation.resources
                .attr("zone",     STRING, 0, 1)
                .ref("ports", "Port", true, 0, -1)
                .build(),
            MClass.builder("VM")     .superType("Host").build(),
            MClass.builder("PM")     .superType("Host").build(),
            MClass.builder("Worker") .superType("Host").build(),

            // Implementation: a reusable build, referenced by any number of Deployables.
            MClass.builder("Implementation").superType("ContextualElement")
                .attr("runtime",   STRING, 0, 1, "container", "process", "function")
                .attr("image",     STRING, 0, 1)
                .attr("resources", MAP,    0, 1)   // {cpu, memory}
                .attr("config",    MAP,    0, 1)   // {KEY: value}
                .build(),

            MClass.builder("Port").superType("ContextualElement")
                .attr("number",   INT,    0, 1)
                .attr("protocol", STRING, 0, 1, "tcp", "udp", "http", "grpc", "https", "grpcs")
                .build(),

            MClass.builder("Connector").superType("ContextualElement")
                .attr("external", BOOLEAN, 0, 1)
                .attr("protocol", STRING,  0, 1, "tcp", "udp", "http", "grpc", "https", "grpcs")
                .build(),

            MClass.builder("Link")
                .attr("portRef",       STRING, 1, 1)
                .attr("connectorName", STRING, 1, 1)
                .attr("direction",     STRING, 0, 1)   // in | out | inout (default inout)
                .build(),

            MClass.builder("Architecture").superType("ContextualElement")
                .ref("components",      "Component",      true, 0, -1)
                .ref("connectors",      "Connector",      true, 0, -1)
                .ref("links",           "Link",           true, 0, -1)
                .ref("implementations", "Implementation", true, 0, -1)
                .build()
        ));
    }
}
