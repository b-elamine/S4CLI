package sam4c.light.metamodel;

import sam4c.light.model.*;
import sam4c.light.model.ref.*;
import sam4c.light.model.rule.*;

import java.util.ArrayList;
import java.util.List;

// Checks a loaded model against the metamodel: required fields, multiplicities,
// enum values and the structural invariants. Each check ties back to an M2 declaration.
public class ConformanceChecker {

    private final MPackage metamodel;

    public ConformanceChecker(MPackage metamodel) {
        this.metamodel = metamodel;
    }

    public List<String> check(Architecture arch) {
        List<String> errors = new ArrayList<>();

        // M2: Architecture.name [0..1] STRING -- optional, no error
        // M2: Architecture -> components [0..*] containment
        for (Component c : arch.components())
            errors.addAll(checkComponent(c, "Architecture.components"));

        // M2: Architecture -> connectors [0..*] containment
        java.util.Set<String> connectorNames = new java.util.HashSet<>();
        for (Connector c : arch.connectors()) {
            if (blank(c.name()))
                errors.add("Connector: name is required (M2: Connector.name [1..1])");
            else connectorNames.add(c.name());
            checkEnum(errors, "Connector '" + c.name() + "'", "Connector", "protocol", c.protocol());
        }

        // M2: Architecture -> implementations [0..*] containment
        for (Implementation impl : arch.implementations()) {
            if (blank(impl.name()))
                errors.add("Implementation: name is required (M2: Implementation.name [1..1])");
            checkEnum(errors, "Implementation '" + impl.name() + "'", "Implementation", "runtime", impl.runtime());
        }

        // Referential integrity: collect every component name and its ports so we
        // can verify links point at things that actually exist. A dangling link
        // (typo'd port or connector) would otherwise survive to generation and
        // produce a broken or phantom deployment artifact.
        java.util.Map<String, java.util.Set<String>> componentPorts = new java.util.HashMap<>();
        collectPorts(arch.components(), componentPorts);

        // M2: Architecture -> links [0..*] containment + referential integrity
        for (Link l : arch.links()) {
            if (blank(l.portRef())) {
                errors.add("Link: portRef is required (M2: Link.portRef [1..1])");
            } else {
                // portRef is "Component.port" (or a bare "Component")
                int dot = l.portRef().indexOf('.');
                String comp = dot < 0 ? l.portRef() : l.portRef().substring(0, dot);
                String port = dot < 0 ? null : l.portRef().substring(dot + 1);
                if (!componentPorts.containsKey(comp))
                    errors.add("Link.portRef '" + l.portRef()
                            + "' references unknown component '" + comp + "'");
                else if (port != null && !componentPorts.get(comp).contains(port))
                    errors.add("Link.portRef '" + l.portRef()
                            + "': component '" + comp + "' has no port '" + port + "'");
            }

            if (blank(l.connectorName()))
                errors.add("Link: connectorName is required (M2: Link.connectorName [1..1])");
            else if (!connectorNames.contains(l.connectorName()))
                errors.add("Link.connectorName '" + l.connectorName()
                        + "' is not a declared connector");
        }

        // Referential integrity for every non-containment reference a type declares
        // (deployedOn -> Host, implementation -> Implementation, ...): derived from
        // allReferences(type), not one hand-written check per reference.
        checkReferences(arch.components(), namesByType(arch), errors);

        return errors;
    }

    /** Recursively collect each component's name -> set of its port names. */
    private void collectPorts(List<Component> components,
                              java.util.Map<String, java.util.Set<String>> out) {
        for (Component c : components) {
            java.util.Set<String> ports = new java.util.HashSet<>();
            for (Port p : c.ports()) ports.add(p.name());
            out.put(c.name(), ports);
            if (!c.members().isEmpty()) collectPorts(c.members(), out);
        }
    }

    /** Every named, typed thing in the architecture, indexed under its own type AND
     *  every supertype it satisfies -- so a reference declared to target "Host" finds
     *  a component whose concrete type is "VM", "PM", or "Worker". */
    private java.util.Map<String, java.util.Set<String>> namesByType(Architecture arch) {
        java.util.Map<String, java.util.Set<String>> byType = new java.util.HashMap<>();
        List<Component> all = new ArrayList<>();
        collectAll(arch.components(), all);
        for (Component c : all) {
            byType.computeIfAbsent(c.type(), t -> new java.util.HashSet<>()).add(c.name());
            for (String superType : metamodel.allSuperTypes(c.type()))
                byType.computeIfAbsent(superType, t -> new java.util.HashSet<>()).add(c.name());
        }
        for (Implementation impl : arch.implementations())
            byType.computeIfAbsent("Implementation", t -> new java.util.HashSet<>()).add(impl.name());
        return byType;
    }

    private void collectAll(List<Component> components, List<Component> out) {
        for (Component c : components) { out.add(c); collectAll(c.members(), out); }
    }

    /** Recursively verify every non-containment reference the component's type declares
     *  resolves to a name of the right target type. */
    private void checkReferences(List<Component> components,
                                 java.util.Map<String, java.util.Set<String>> namesByType, List<String> errors) {
        for (Component c : components) {
            for (MReference r : metamodel.allReferences(c.type())) {
                if (r.containment()) continue;
                Object target = c.properties().get(r.name());
                if (target == null) continue;
                java.util.Set<String> valid = namesByType.getOrDefault(r.targetClass(), java.util.Set.of());
                if (!valid.contains(target.toString()))
                    errors.add("Component(" + c.name() + "): " + r.name() + " '" + target
                            + "' does not resolve to a declared " + r.targetClass()
                            + " (M2: " + c.type() + "." + r.name() + " -> " + r.targetClass() + ")");
            }
            if (!c.members().isEmpty()) checkReferences(c.members(), namesByType, errors);
        }
    }

    public List<String> check(SecurityModel sec) {
        List<String> errors = new ArrayList<>();

        // M2: AttributeType.name [1..1]
        for (AttributeType a : sec.attributes()) {
            if (blank(a.name()))
                errors.add("AttributeType: name is required (M2: AttributeType.name [1..1])");
        }

        // M2: NamedContext.name [1..1], NamedContext -> conditions [1..*]
        for (NamedContext ctx : sec.contexts()) {
            if (blank(ctx.name()))
                errors.add("NamedContext: name is required (M2: NamedContext.name [1..1])");
            if (ctx.conditions().isEmpty())
                errors.add("NamedContext '" + ctx.name()
                        + "': at least one condition required (M2: NamedContext.conditions [1..*])");
            for (Ref r : ctx.conditions())
                errors.addAll(checkRef(r, "NamedContext(" + ctx.name() + ").conditions"));
        }

        // M2: SecurityRule subclass constraints
        for (SecurityRule rule : sec.rules())
            errors.addAll(checkRule(rule));

        return errors;
    }

    // -------------------------------------------------------------------------
    // Component
    // -------------------------------------------------------------------------

    private List<String> checkComponent(Component c, String location) {
        List<String> errors = new ArrayList<>();
        String ctx = location + " > Component(" + c.name() + ")";

        // M2: Component.name [0..1] -- optional, skip
        // M2: Component.type [1..1]
        if (blank(c.type()))
            errors.add(ctx + ": type is required (M2: Component.type [1..1])");

        // M2: Component -> ports [0..*] -- each port needs a name; protocol from allowed set
        for (Port p : c.ports()) {
            if (blank(p.name()))
                errors.add(ctx + ": Port.name is required (M2: Port.name [1..1])");
            checkEnum(errors, ctx + ": Port '" + p.name() + "'", "Port", "protocol", p.protocol());
            if (p.number() != null && (p.number() < 1 || p.number() > 65535))
                errors.add(ctx + ": Port '" + p.name() + "' number " + p.number()
                        + " out of range (1..65535)");
        }

        // M2: Deployable features (carried in the properties map) -- enum value sets are
        // read from the metamodel's `allowed` declarations (single source of truth).
        Object exposure = c.properties().get("exposure");
        checkEnum(errors, ctx, c.type(), "exposure",  exposure);
        checkEnum(errors, ctx, c.type(), "lifecycle", c.properties().get("lifecycle"));
        checkEnum(errors, ctx, c.type(), "spread",    c.properties().get("spread"));

        // Well-formedness invariants (C4)
        // (6) an exposed deployable must have at least one port
        if (exposure != null && !exposure.toString().equalsIgnoreCase("none") && c.ports().isEmpty())
            errors.add(ctx + ": exposure '" + exposure + "' but the deployable has no ports");
        // (7) a persistent Data deployable must declare storage
        if (Boolean.TRUE.equals(c.properties().get("persistent")) && c.properties().get("storage") == null)
            errors.add(ctx + ": persistent=true but no storage declared");
        // (8) scale: min <= replicas <= max
        if (c.properties().get("scale") instanceof java.util.Map<?, ?> sc) {
            Integer min = asInt(sc.get("min")), rep = asInt(sc.get("replicas")), max = asInt(sc.get("max"));
            if (min != null && max != null && min > max)
                errors.add(ctx + ": scale.min (" + min + ") > scale.max (" + max + ")");
            if (rep != null && min != null && rep < min)
                errors.add(ctx + ": scale.replicas (" + rep + ") < scale.min (" + min + ")");
            if (rep != null && max != null && rep > max)
                errors.add(ctx + ": scale.replicas (" + rep + ") > scale.max (" + max + ")");
        }

        // M2: verify type is a known concrete subclass of Component
        boolean knownType = metamodel.isA(c.type(), "Component");
        if (!knownType)
            errors.add(ctx + ": unknown type '" + c.type()
                    + "'. Must conform to M2 Component hierarchy.");

        // Well-formedness: Colocation containment rules
        for (Component member : c.members()) {
            if (c.type().equals("Colocation") && !metamodel.isA(member.type(), "Deployable"))
                errors.add(ctx + ": Colocation may contain only Deployables, not '"
                        + member.name() + "' (" + member.type() + ")");
            // (9) the deployable unit is the group: a member must not carry its own scale
            if (c.type().equals("Colocation") && member.properties().get("scale") != null)
                errors.add(ctx + ": member '" + member.name() + "' has its own scale; "
                        + "scale belongs to the Colocation");
        }

        // M2: Component -> members [0..*] containment -- recurse
        for (Component member : c.members())
            errors.addAll(checkComponent(member, ctx + ".members"));

        return errors;
    }

    // -------------------------------------------------------------------------
    // Security rules
    // -------------------------------------------------------------------------

    private List<String> checkRule(SecurityRule rule) {
        List<String> errors = new ArrayList<>();
        switch (rule) {

            // M2: Confidentiality.sctx [1..1], tctx [0..1]
            case Confidentiality r -> {
                if (r.sctx() == null)
                    errors.add("Confidentiality: sctx is required (M2: Confidentiality.sctx [1..1])");
                else errors.addAll(checkRef(r.sctx(), "Confidentiality.sctx"));
                if (r.tctx() != null)
                    errors.addAll(checkRef(r.tctx(), "Confidentiality.tctx"));
            }

            // M2: Integrity.sctx [1..1], tctx [0..1]
            case Integrity r -> {
                if (r.sctx() == null)
                    errors.add("Integrity: sctx is required (M2: Integrity.sctx [1..1])");
                else errors.addAll(checkRef(r.sctx(), "Integrity.sctx"));
                if (r.tctx() != null)
                    errors.addAll(checkRef(r.tctx(), "Integrity.tctx"));
            }

            // M2: Isolation.sctx [1..1], tctx [0..1]
            case Isolation r -> {
                if (r.sctx() == null)
                    errors.add("Isolation: sctx is required (M2: Isolation.sctx [1..1])");
                else errors.addAll(checkRef(r.sctx(), "Isolation.sctx"));
                if (r.tctx() != null)
                    errors.addAll(checkRef(r.tctx(), "Isolation.tctx"));
            }

            // M2: Authentication.sctx [1..1], actx [1..1], tctx [1..1]
            case Authentication r -> {
                if (r.sctx() == null)
                    errors.add("Authentication: sctx is required (M2: Authentication.sctx [1..1])");
                else errors.addAll(checkRef(r.sctx(), "Authentication.sctx"));
                if (r.actx() == null)
                    errors.add("Authentication: actx is required (M2: Authentication.actx [1..1])");
                else errors.addAll(checkRef(r.actx(), "Authentication.actx"));
                if (r.tctx() == null)
                    errors.add("Authentication: tctx is required (M2: Authentication.tctx [1..1])");
                else errors.addAll(checkRef(r.tctx(), "Authentication.tctx"));
            }

            // M2: Authorization.subject [1..1], resource [1..1], action [1..1]
            case Authorization r -> {
                if (r.subject() == null)
                    errors.add("Authorization: subject is required (M2: Authorization.subject [1..1])");
                else errors.addAll(checkRef(r.subject(), "Authorization.subject"));
                if (r.resource() == null)
                    errors.add("Authorization: resource is required (M2: Authorization.resource [1..1])");
                else errors.addAll(checkRef(r.resource(), "Authorization.resource"));
                if (r.actions() == null || r.actions().isEmpty())
                    errors.add("Authorization: at least one action is required (M2: Authorization.action [1..*])");
                else if (r.actions().stream().anyMatch(ConformanceChecker::blank))
                    errors.add("Authorization: action names must be non-blank");
            }

            // M2: Availability.target [1..1], level [1..1] in {high, medium, low}
            case Availability r -> {
                if (r.target() == null)
                    errors.add("Availability: target is required (M2: Availability.target [1..1])");
                else errors.addAll(checkRef(r.target(), "Availability.target"));
                if (blank(r.level()))
                    errors.add("Availability: level is required (M2: Availability.level [1..1])");
                else if (!java.util.Set.of("high", "medium", "low").contains(r.level().toLowerCase()))
                    errors.add("Availability: level '" + r.level()
                            + "' invalid (M2: Availability.level in high|medium|low)");
            }
        }
        return errors;
    }

    // -------------------------------------------------------------------------
    // Refs
    // -------------------------------------------------------------------------

    private List<String> checkRef(Ref ref, String location) {
        List<String> errors = new ArrayList<>();
        switch (ref) {

            // M2: NamedRef.name [1..1]
            case NamedRef r -> {
                if (blank(r.name()))
                    errors.add(location + ": NamedRef.name is required (M2: NamedRef.name [1..1])");
            }

            // M2: ValuedAttrRef.attribute [1..1], ValuedAttrRef.value [1..1]
            case ValuedAttrRef r -> {
                if (blank(r.attribute()))
                    errors.add(location + ": ValuedAttrRef.attribute is required");
                if (blank(r.value()))
                    errors.add(location + ": ValuedAttrRef.value is required");
            }

            // M2: ComposedRef.conditions [2..*]
            case ComposedRef r -> {
                if (r.conditions().size() < 2)
                    errors.add(location + ": ComposedRef requires at least 2 conditions "
                            + "(M2: ComposedRef.conditions [2..*])");
                for (Ref inner : r.conditions())
                    errors.addAll(checkRef(inner, location + ".conditions"));
            }
        }
        return errors;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // check an enum value against the allowed set declared on that attribute in M2
    private static void checkEnum(List<String> errors, String ctx, String type, String attr, Object value) {
        if (value == null) return;
        List<String> allowed = Sam4cMetamodel.INSTANCE.allAttributes(type).stream()
                .filter(a -> a.name().equals(attr)).findFirst()
                .map(MAttribute::allowed).orElse(List.of());
        if (!allowed.isEmpty() && !allowed.contains(value.toString().toLowerCase()))
            errors.add(ctx + ": " + attr + " '" + value + "' invalid (M2: " + type + "." + attr
                    + " ∈ " + String.join("|", allowed) + ")");
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? null : Integer.valueOf(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
