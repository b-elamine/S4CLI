package sam4c.light.merger;

import sam4c.light.model.*;
import sam4c.light.model.ref.*;
import sam4c.light.model.rule.*;

import java.util.*;
import java.util.stream.Collectors;

public class ModelMerger {

    /**
     * Produces a UnifiedModel where every security rule holds direct Component
     * references -- not names, not IDs, but the actual Java objects from the
     * Architecture. This is the in-memory object graph: navigating a rule gives
     * you the full Component (ports, children, attributes) without any lookup.
     *
     * The JSON serialization uses names as cross-reference handles, exactly as
     * XMI uses object IDs. Generators work on this in-memory graph, not the JSON.
     */
    public static UnifiedModel merge(Architecture arch, SecurityModel sec) {
        List<Component> all = flattenComponents(arch.components());

        Map<String, List<Component>> coverage = buildCoverage(all, sec);
        Map<String, List<Link>> linksByConnector = buildLinksByConnector(arch.links());
        Map<String, Set<String>> componentToConnectors = buildComponentToConnectors(linksByConnector);
        Map<String, Integer> connectorOrder = buildConnectorOrder(arch.connectors());

        List<String> unresolved = new ArrayList<>();
        List<ResolvedRule> resolvedRules = sec.rules().stream()
                .map(rule -> resolveRule(rule, coverage, all, linksByConnector, componentToConnectors, connectorOrder, unresolved))
                .collect(Collectors.toList());

        return new UnifiedModel(arch, sec, coverage, resolvedRules, unresolved);
    }

    // -------------------------------------------------------------------------
    // Coverage: context name -> actual Component objects that satisfy it
    // -------------------------------------------------------------------------

    private static Map<String, List<Component>> buildCoverage(List<Component> all,
                                                               SecurityModel sec) {
        Map<String, List<Component>> coverage = new LinkedHashMap<>();
        for (NamedContext ctx : sec.contexts()) {
            List<Component> matching = all.stream()
                    .filter(c -> allConditionsMatch(c, ctx.conditions(), all))
                    .collect(Collectors.toList());
            coverage.put(ctx.name(), matching);
        }
        return coverage;
    }

    private static boolean allConditionsMatch(Component c, List<Ref> conditions,
                                               List<Component> all) {
        for (Ref condition : conditions) {
            if (!refMatches(c, condition, all)) return false;
        }
        return true;
    }

    private static boolean refMatches(Component c, Ref ref, List<Component> all) {
        return switch (ref) {
            case ValuedAttrRef r -> r.value().equals(c.attributes().get(r.attribute()));
            case NamedRef r      -> c.name().equals(r.name());
            case ComposedRef r   -> r.conditions().stream()
                    .allMatch(cond -> refMatches(c, cond, all));
        };
    }

    // -------------------------------------------------------------------------
    // Rule resolution: attach actual Component objects to each rule
    // -------------------------------------------------------------------------

    private static ResolvedRule resolveRule(SecurityRule rule,
                                             Map<String, List<Component>> coverage,
                                             List<Component> all,
                                             Map<String, List<Link>> linksByConnector,
                                             Map<String, Set<String>> componentToConnectors,
                                             Map<String, Integer> connectorOrder,
                                             List<String> unresolved) {
        // resolve each rule's argument slots to components (actx only for Authentication)
        List<Component> sctx = List.of(), tctx = List.of(), actx = List.of();
        switch (rule) {
            case Confidentiality r -> { sctx = resolveRef(r.sctx(), coverage, all, unresolved);
                                        tctx = resolveRef(r.tctx(), coverage, all, unresolved); }
            case Integrity r       -> { sctx = resolveRef(r.sctx(), coverage, all, unresolved);
                                        tctx = resolveRef(r.tctx(), coverage, all, unresolved); }
            case Isolation r       -> { sctx = resolveRef(r.sctx(), coverage, all, unresolved);
                                        tctx = resolveRef(r.tctx(), coverage, all, unresolved); }
            case Authentication r  -> { sctx = resolveRef(r.sctx(), coverage, all, unresolved);
                                        tctx = resolveRef(r.tctx(), coverage, all, unresolved);
                                        actx = resolveRef(r.actx(), coverage, all, unresolved); }
            case Authorization r   -> { sctx = resolveRef(r.subject(), coverage, all, unresolved);   // who
                                        tctx = resolveRef(r.resource(), coverage, all, unresolved); } // what
            case Availability r    -> { sctx = resolveRef(r.target(), coverage, all, unresolved); }   // single context, no path
        }
        List<ResolvedPath> paths = resolvePaths(sctx, tctx, linksByConnector, componentToConnectors, connectorOrder);
        return new ResolvedRule(rule, sctx, tctx, actx, paths);
    }

    // Which connector(s) actually carry traffic between the two sides: a path exists when
    // an sctx and a tctx component hang off the same connector. Records the connector and
    // the links on each side, so a generator knows where to put a control.
    //
    // Rather than scanning every architecture connector per rule, narrow to the connectors
    // the sctx and tctx components actually touch (via componentToConnectors, built once per
    // merge) and intersect the two sides -- a path can only exist on a connector both sides
    // reach. connectorOrder restores the architecture's declared connector order in the
    // result, so output is identical to a full scan, just without paying for one.
    private static List<ResolvedPath> resolvePaths(List<Component> sctx,
                                                   List<Component> tctx,
                                                   Map<String, List<Link>> linksByConnector,
                                                   Map<String, Set<String>> componentToConnectors,
                                                   Map<String, Integer> connectorOrder) {
        if (sctx.isEmpty() || tctx.isEmpty()) return List.of();

        Set<String> sNames = sctx.stream().map(Component::name).collect(Collectors.toSet());
        Set<String> tNames = tctx.stream().map(Component::name).collect(Collectors.toSet());

        Set<String> sConnectors = sNames.stream()
                .flatMap(n -> componentToConnectors.getOrDefault(n, Set.of()).stream())
                .collect(Collectors.toSet());
        List<String> candidates = tNames.stream()
                .flatMap(n -> componentToConnectors.getOrDefault(n, Set.of()).stream())
                .filter(sConnectors::contains)
                .distinct()
                .sorted(Comparator.comparingInt(connectorOrder::get))
                .collect(Collectors.toList());

        List<ResolvedPath> paths = new ArrayList<>();
        for (String connName : candidates) {
            List<Link> sLinks = new ArrayList<>();
            List<Link> tLinks = new ArrayList<>();
            for (Link l : linksByConnector.getOrDefault(connName, List.of())) {
                String comp = componentOf(l.portRef());
                if (sNames.contains(comp)) sLinks.add(l);
                if (tNames.contains(comp)) tLinks.add(l);
            }
            // A real path needs at least one link from each side on this connector
            if (!sLinks.isEmpty() && !tLinks.isEmpty())
                paths.add(new ResolvedPath(connName, sLinks, tLinks));
        }
        return paths;
    }

    /** Groups links by connector name, once per merge, preserving arch.links() order within each bucket. */
    private static Map<String, List<Link>> buildLinksByConnector(List<Link> links) {
        Map<String, List<Link>> byConnector = new LinkedHashMap<>();
        for (Link l : links) {
            byConnector.computeIfAbsent(l.connectorName(), k -> new ArrayList<>()).add(l);
        }
        return byConnector;
    }

    /** Component name -> the set of connector names it has at least one link on. Built once per merge. */
    private static Map<String, Set<String>> buildComponentToConnectors(Map<String, List<Link>> linksByConnector) {
        Map<String, Set<String>> byComponent = new HashMap<>();
        for (Map.Entry<String, List<Link>> e : linksByConnector.entrySet()) {
            for (Link l : e.getValue()) {
                byComponent.computeIfAbsent(componentOf(l.portRef()), k -> new HashSet<>()).add(e.getKey());
            }
        }
        return byComponent;
    }

    /** Connector name -> its position in the architecture's declared connector order. */
    private static Map<String, Integer> buildConnectorOrder(List<Connector> connectors) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < connectors.size(); i++) order.put(connectors.get(i).name(), i);
        return order;
    }

    /** Component name part of a "Component.port" reference (or the whole string if no dot). */
    private static String componentOf(String portRef) {
        int dot = portRef.indexOf('.');
        return dot < 0 ? portRef : portRef.substring(0, dot);
    }

    /**
     * Resolves a Ref to the actual Component objects it points to.
     *
     *   NamedRef("frontendCtx")          -> looks up coverage -> [Component(FrontendVM), Component(Nginx)]
     *   NamedRef("FrontendVM")           -> direct reference  -> [Component(FrontendVM)]
     *   ValuedAttrRef(Domain, frontend)  -> inline predicate  -> [Component(FrontendVM), Component(Nginx)]
     *   ComposedRef([...])               -> inline predicate  -> matching Component objects
     */
    private static List<Component> resolveRef(Ref ref,
                                               Map<String, List<Component>> coverage,
                                               List<Component> all,
                                               List<String> unresolved) {
        if (ref == null) return List.of();

        return switch (ref) {
            case NamedRef r -> {
                if (coverage.containsKey(r.name())) {
                    yield new ArrayList<>(coverage.get(r.name()));
                }
                Optional<Component> direct = all.stream()
                        .filter(c -> c.name().equals(r.name()))
                        .findFirst();
                if (direct.isPresent()) yield List.of(direct.get());
                if (!unresolved.contains(r.name())) unresolved.add(r.name());
                yield List.of();
            }
            case ValuedAttrRef r -> all.stream()
                    .filter(c -> r.value().equals(c.attributes().get(r.attribute())))
                    .collect(Collectors.toList());
            case ComposedRef r -> all.stream()
                    .filter(c -> r.conditions().stream()
                            .allMatch(cond -> refMatches(c, cond, all)))
                    .collect(Collectors.toList());
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<Component> flattenComponents(List<Component> components) {
        List<Component> flat = new ArrayList<>();
        for (Component c : components) {
            flat.add(c);
            if (!c.children().isEmpty())
                flat.addAll(flattenComponents(c.children()));
        }
        return flat;
    }
}
