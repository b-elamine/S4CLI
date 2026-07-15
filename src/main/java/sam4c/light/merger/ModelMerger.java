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

        List<String> unresolved = new ArrayList<>();
        List<ResolvedRule> resolvedRules = sec.rules().stream()
                .map(rule -> resolveRule(rule, coverage, all, arch, linksByConnector, unresolved))
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
                                             Architecture arch,
                                             Map<String, List<Link>> linksByConnector,
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
        List<ResolvedPath> paths = resolvePaths(sctx, tctx, arch, linksByConnector);
        return new ResolvedRule(rule, sctx, tctx, actx, paths);
    }

    // Which connector(s) actually carry traffic between the two sides: a path exists when
    // an sctx and a tctx component hang off the same connector. Records the connector and
    // the links on each side, so a generator knows where to put a control.
    //
    // linksByConnector is built once per merge (see buildLinksByConnector), not rescanned
    // here per connector: doing so per rule made this O(connectors x links) per call.
    private static List<ResolvedPath> resolvePaths(List<Component> sctx,
                                                   List<Component> tctx,
                                                   Architecture arch,
                                                   Map<String, List<Link>> linksByConnector) {
        if (sctx.isEmpty() || tctx.isEmpty()) return List.of();

        Set<String> sNames = sctx.stream().map(Component::name).collect(Collectors.toSet());
        Set<String> tNames = tctx.stream().map(Component::name).collect(Collectors.toSet());

        List<ResolvedPath> paths = new ArrayList<>();
        for (Connector conn : arch.connectors()) {
            List<Link> sLinks = new ArrayList<>();
            List<Link> tLinks = new ArrayList<>();
            for (Link l : linksByConnector.getOrDefault(conn.name(), List.of())) {
                String comp = componentOf(l.portRef());
                if (sNames.contains(comp)) sLinks.add(l);
                if (tNames.contains(comp)) tLinks.add(l);
            }
            // A real path needs at least one link from each side on this connector
            if (!sLinks.isEmpty() && !tLinks.isEmpty())
                paths.add(new ResolvedPath(conn.name(), sLinks, tLinks));
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
