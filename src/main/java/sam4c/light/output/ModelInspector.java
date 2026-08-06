package sam4c.light.output;

import sam4c.light.model.*;
import sam4c.light.model.rule.*;

import java.util.List;

/**
 * Traverses the in-memory object graph of a UnifiedModel the way a generator
 * would. Every access here is a direct Java field navigation -- no string
 * lookups, no map queries, no JSON parsing. The Component objects inside each
 * ResolvedRule are the same instances as in Architecture, shared by reference.
 */
public class ModelInspector {

    public static void inspect(UnifiedModel model) {
        sep();
        System.out.println("  MODEL GRAPH TRAVERSAL -- " + model.architecture().name().toUpperCase());
        sep();

        System.out.println("\n  ARCHITECTURE");
        System.out.println("  " + "-".repeat(50));
        for (Component top : model.architecture().components()) {
            printComponent(top, "  ");
        }
        System.out.println("\n  Connectors:");
        for (Connector c : model.architecture().connectors()) {
            System.out.printf("    [%s]%s%n", c.name(), c.external() ? "  (external)" : "");
        }
        System.out.println("\n  Links:");
        for (Link l : model.architecture().links()) {
            System.out.printf("    %s  <-->  [%s]%n", l.portRef(), l.connectorName());
        }

        System.out.println("\n\n  SECURITY RULES -- FULLY RESOLVED OBJECT GRAPH");
        System.out.println("  " + "-".repeat(50));
        int i = 1;
        for (ResolvedRule rr : model.resolvedRules()) {
            System.out.printf("%n  Rule #%d : %s%n", i++, rr.rule().getClass().getSimpleName());
            printRuleDetail(rr);
        }

        if (!model.unresolved().isEmpty()) {
            System.out.println("\n  UNRESOLVED REFERENCES");
            System.out.println("  " + "-".repeat(50));
            model.unresolved().forEach(n -> System.out.println("    x " + n));
        }

        sep();
    }

    private static void printRuleDetail(ResolvedRule rr) {
        switch (rr.rule()) {
            case Confidentiality ignored -> {
                System.out.println("    Meaning: communication must be encrypted");
                printSide("sctx", rr.sctxComponents());
                printSide("tctx", rr.tctxComponents());
                System.out.println("    Generator hint: enforce TLS on every link between sctx and tctx components");
            }
            case Integrity ignored -> {
                System.out.println("    Meaning: data in transit must not be alterable");
                printSide("sctx", rr.sctxComponents());
                printSide("tctx", rr.tctxComponents());
                System.out.println("    Generator hint: enable integrity-checked channels between sctx and tctx");
            }
            case Isolation ignored -> {
                System.out.println("    Meaning: no network path allowed between the two sides");
                printSide("sctx", rr.sctxComponents());
                printSide("tctx", rr.tctxComponents());
                System.out.println("    Generator hint: add deny-all rules between every sctx and tctx component");
            }
            case Authentication ignored -> {
                System.out.println("    Meaning: sctx must authenticate via actx before reaching tctx");
                printSide("sctx (who)",  rr.sctxComponents());
                printSide("actx (how)",  rr.actxComponents());
                printSide("tctx (what)", rr.tctxComponents());
                System.out.println("    Generator hint: put an auth gate on every tctx port that serves sctx traffic");
            }
            case Authorization r -> {
                String acts = String.join(", ", r.actions());
                System.out.println("    Meaning: subject may perform [" + acts + "] on resource");
                printSide("subject (who)",   rr.sctxComponents());
                printSide("resource (what)", rr.tctxComponents());
                System.out.println("    Generator hint: emit an RBAC/IAM policy allowing [" + acts + "] from subject to resource");
            }
            case Availability r -> {
                System.out.println("    Meaning: target must stay available at level [" + r.level() + "]");
                printSide("target", rr.sctxComponents());
                System.out.println("    Generator hint: enforce min replicas / PodDisruptionBudget / multi-zone spread on target");
            }
        }
        printPaths(rr);
    }

    // The concrete connector paths this rule governs -- the generator's anchor point.
    private static void printPaths(ResolvedRule rr) {
        if (rr.paths().isEmpty()) {
            System.out.println("    Paths        : (none -- sides share no connector)");
            return;
        }
        System.out.println("    Paths        :");
        for (ResolvedPath path : rr.paths()) {
            System.out.printf("      via connector '%s'%n", path.connector());
            for (Link l : path.sctxLinks())
                System.out.printf("        sctx %s [%s]%n", l.portRef(), l.direction().token());
            for (Link l : path.tctxLinks())
                System.out.printf("        tctx %s [%s]%n", l.portRef(), l.direction().token());
        }
    }

    private static void printSide(String label, List<Component> components) {
        if (components.isEmpty()) {
            System.out.printf("    %-14s (none)%n", label + ":");
            return;
        }
        System.out.printf("    %-14s%n", label + ":");
        for (Component c : components) {
            System.out.printf("      + %-20s  type=%-6s  attrs=%s%n",
                    c.name(), c.type(), c.attributes());
            if (!c.ports().isEmpty()) {
                System.out.printf("        %-22s  ports=%s%n", "",
                        c.ports().stream().map(Port::name).toList());
            }
            if (!c.members().isEmpty()) {
                System.out.printf("        %-22s  members=%s%n", "",
                        c.members().stream().map(Component::name).toList());
            }
        }
    }

    private static void printComponent(Component c, String indent) {
        System.out.printf("%s[%s] %s  attrs=%s%n",
                indent, c.type(), c.name(), c.attributes().isEmpty() ? "{}" : c.attributes());
        if (!c.ports().isEmpty())
            System.out.printf("%s  ports: %s%n", indent,
                    c.ports().stream().map(Port::name).toList());
        for (Component member : c.members())
            printComponent(member, indent + "    ");
    }

    private static void sep() {
        System.out.println("\n" + "=".repeat(60));
    }
}
