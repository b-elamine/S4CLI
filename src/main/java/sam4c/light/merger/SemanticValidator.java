package sam4c.light.merger;

import sam4c.light.model.*;
import sam4c.light.model.rule.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Runs after merge. Produces warnings (not hard errors) about the resolved model:
// rules/contexts that match nothing, and whether the design actually delivers what a
// rule asks for. This is the security analysis layer; conformance only checks shape.
public class SemanticValidator {

    // empty list = nothing to flag
    public static List<String> validate(UnifiedModel model) {
        List<String> warnings = new ArrayList<>();

        List<Component> all = new ArrayList<>();
        collect(model.architecture().components(), all);

        // component -> connector names it has at least one link on, and the reverse index.
        // Together these let us walk the component-adjacency graph (two components are
        // adjacent if they share a connector) without rescanning links per rule.
        Map<String, Set<String>> componentConnectors = new HashMap<>();
        Map<String, Set<String>> connectorComponents = new HashMap<>();
        for (Link l : model.architecture().links()) {
            String comp = componentOf(l.portRef());
            componentConnectors.computeIfAbsent(comp, k -> new HashSet<>()).add(l.connectorName());
            connectorComponents.computeIfAbsent(l.connectorName(), k -> new HashSet<>()).add(comp);
        }
        Map<String, Connector> connectorByName = new HashMap<>();
        for (Connector cn : model.architecture().connectors()) connectorByName.put(cn.name(), cn);

        // rules whose arguments match no components (typo, missing tag, or unused policy)
        for (ResolvedRule rr : model.resolvedRules()) {
            String type = rr.rule().getClass().getSimpleName();

            if (rr.sctxComponents().isEmpty())
                warnings.add(type + ": sctx resolves to no components "
                        + "-- unused rule, or a missing/mistyped tag");

            // only complain about tctx when the rule actually has one
            if (tctxSpecified(rr.rule()) && rr.tctxComponents().isEmpty())
                warnings.add(type + ": tctx was specified but resolves to no components");

            if (rr.rule() instanceof Authentication && rr.actxComponents().isEmpty())
                warnings.add(type + ": actx resolves to no components");
        }

        // contexts that match nothing in this architecture
        for (Map.Entry<String, List<Component>> e : model.coverage().entrySet()) {
            if (e.getValue().isEmpty())
                warnings.add("context '" + e.getKey() + "' matches no components "
                        + "-- predicate satisfied by nothing in the architecture");
        }

        // Isolation says "no path allowed". By default "path" is single-hop (one shared
        // connector, what merge already resolved into rr.paths()). When a rule names a
        // via context (an approved mediator), the check switches to full multi-hop
        // reachability over the component-adjacency graph: a path that must pass through
        // a via component is the intended, legitimate route and is not a violation, but
        // any path that reaches the target WITHOUT touching a via component is.
        for (ResolvedRule rr : model.resolvedRules()) {
            if (!(rr.rule() instanceof Isolation)) continue;
            if (!rr.actxComponents().isEmpty()) {
                Set<String> via = names(rr.actxComponents());
                List<String> bypass = pathAvoiding(names(rr.sctxComponents()), names(rr.tctxComponents()),
                        via, componentConnectors, connectorComponents);
                if (bypass != null)
                    warnings.add("Isolation violated: a multi-hop path exists (" + String.join(" -> ", bypass)
                            + ") that bypasses the approved mediator '" + String.join(",", via) + "' "
                            + "-- they are not isolated");
            } else if (!rr.paths().isEmpty()) {
                warnings.add("Isolation violated: a network path exists via connector '"
                        + rr.paths().get(0).connector() + "' between the two sides "
                        + "-- they are not isolated");
            }
        }

        // Authentication: a target is only guarded if the authenticating context actually
        // sits on the path from source to target -- as the target itself, or as a real
        // mediator between them -- not merely because the rule names it. If the source can
        // still reach the target while avoiding every actx component, the rule is declared
        // but not enforced by the topology, and the target does not count as authenticated.
        Set<String> authenticated = new HashSet<>();
        for (ResolvedRule rr : model.resolvedRules()) {
            if (!(rr.rule() instanceof Authentication)) continue;
            Set<String> src = names(rr.sctxComponents()), tgt = names(rr.tctxComponents());
            Set<String> gate = names(rr.actxComponents());
            if (src.isEmpty() || tgt.isEmpty() || gate.isEmpty()) continue;
            List<String> bypass = pathAvoiding(src, tgt, gate, componentConnectors, connectorComponents);
            if (bypass != null)
                warnings.add("Authentication requires access from '" + String.join(",", src) + "' to '"
                        + String.join(",", tgt) + "' to pass through '" + String.join(",", gate)
                        + "', but a path (" + String.join(" -> ", bypass) + ") bypasses it "
                        + "-- authentication is declared, not enforced by the topology");
            else
                tgt.forEach(authenticated::add);
        }

        // connectors marked external, and which components are wired to them
        Set<String> externalConnectors = new HashSet<>();
        for (Connector cn : model.architecture().connectors())
            if (cn.external()) externalConnectors.add(cn.name());
        Set<String> onExternal = new HashSet<>();
        for (Link l : model.architecture().links()) {
            if (!externalConnectors.contains(l.connectorName())) continue;
            String ref = l.portRef();
            onExternal.add(ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref);
        }

        // missing authentication: an internet-exposed workload with nothing guarding it
        for (Component c : all) {
            if (isDeployable(c)
                    && "external".equalsIgnoreCase(String.valueOf(c.properties().get("exposure")))
                    && !authenticated.contains(c.name()))
                warnings.add("'" + c.name() + "' is exposed (exposure: external) but no "
                        + "Authentication rule guards it -- unauthenticated entry point");
        }

        // a data store facing the external sphere (external exposure, or on an external connector)
        for (Component c : all) {
            if (!c.type().equals("Data")) continue;
            if ("external".equalsIgnoreCase(String.valueOf(c.properties().get("exposure")))
                    || onExternal.contains(c.name()))
                warnings.add("Data store '" + c.name() + "' faces the external sphere "
                        + "-- a stateful store should not be directly exposed");
        }

        // declared internal/none but wired to an external connector -> unintended exposure
        for (Component c : all) {
            if (!isDeployable(c)) continue;
            String exp = String.valueOf(c.properties().get("exposure"));
            if (!"external".equalsIgnoreCase(exp) && onExternal.contains(c.name()))
                warnings.add("'" + c.name() + "' is declared exposure="
                        + (c.properties().get("exposure") == null ? "none" : exp)
                        + " but is wired to an external connector -- unintended exposure");
        }

        // Authorization granting access to a resource that no Authentication guards
        for (ResolvedRule rr : model.resolvedRules()) {
            if (!(rr.rule() instanceof Authorization)) continue;
            for (Component res : rr.tctxComponents())
                if (!authenticated.contains(res.name()))
                    warnings.add("Authorization grants access to '" + res.name()
                            + "' but no Authentication rule guards it -- access without authentication");
        }

        // Isolation and a communication rule over the same pair = contradictory policy
        for (ResolvedRule iso : model.resolvedRules()) {
            if (!(iso.rule() instanceof Isolation)) continue;
            Set<String> a = names(iso.sctxComponents()), b = names(iso.tctxComponents());
            if (a.isEmpty() || b.isEmpty()) continue;
            for (ResolvedRule comm : model.resolvedRules()) {
                if (comm == iso || !connects(comm.rule())) continue;
                Set<String> cs = names(comm.sctxComponents()), ct = names(comm.tctxComponents());
                if ((overlaps(a, cs) && overlaps(b, ct)) || (overlaps(a, ct) && overlaps(b, cs)))
                    warnings.add("Isolation requires no path between two sides that "
                            + comm.rule().getClass().getSimpleName()
                            + " connects -- contradictory policy");
            }
        }

        // Confidentiality/Integrity: the resolved channel must actually be protected. Both
        // resolve to real components and a real connector path (recorded in rr.paths()) --
        // this checks that every connector on that path uses an encrypted protocol, rather
        // than treating a resolved rule as satisfied by construction.
        for (ResolvedRule rr : model.resolvedRules()) {
            if (!(rr.rule() instanceof Confidentiality) && !(rr.rule() instanceof Integrity)) continue;
            String kind = rr.rule() instanceof Confidentiality ? "Confidentiality" : "Integrity";
            for (ResolvedPath p : rr.paths()) {
                Connector cn = connectorByName.get(p.connector());
                if (cn == null || !isEncrypted(cn.protocol()))
                    warnings.add(kind + " requires a protected channel but connector '" + p.connector()
                            + "' is plaintext (protocol: "
                            + (cn == null || cn.protocol() == null ? "unspecified" : cn.protocol())
                            + ") -- data crosses this connector unprotected");
            }
        }

        // Availability: medium needs >=2 copies, high also needs zone spread, or the
        // requirement isn't really met.
        Map<String, Integer> replicas = effectiveReplicas(model.architecture());
        Map<String, String>  spread   = effectiveSpread(model.architecture());
        for (ResolvedRule rr : model.resolvedRules()) {
            if (!(rr.rule() instanceof Availability av)) continue;
            String level = av.level() == null ? "" : av.level().toLowerCase();
            if (level.equals("low")) continue;   // low = no availability requirement
            for (Component c : rr.sctxComponents()) {
                if (!isDeployable(c)) continue;     // only workloads run as replicated copies
                Integer eff = replicas.get(c.name());
                int n = (eff == null) ? 1 : eff;  // no scale declared anywhere = a single copy
                if (n < 2)
                    warnings.add("Availability=" + level + " but '" + c.name() + "' has "
                            + n + " copy" + (n == 1 ? "" : "ies") + " (needs >= 2) "
                            + "-- single point of failure, requirement not satisfied");
                if (level.equals("high") && !"zone".equalsIgnoreCase(spread.get(c.name())))
                    warnings.add("Availability=high but '" + c.name() + "' is not spread across zones "
                            + "(set spread: zone on the workload or its Colocation) "
                            + "-- a whole-zone outage would take it down");
            }
        }

        return warnings;
    }

    private static boolean isDeployable(Component c) {
        return c.type().equals("App") || c.type().equals("Data");
    }

    private static boolean isEncrypted(String protocol) {
        if (protocol == null) return false;
        return switch (protocol.toLowerCase()) {
            case "https", "grpcs", "tls", "wss" -> true;
            default -> false;
        };
    }

    // flatten the component tree into one list
    private static void collect(List<Component> comps, List<Component> out) {
        for (Component c : comps) { out.add(c); collect(c.members(), out); }
    }

    private static Set<String> names(List<Component> comps) {
        Set<String> s = new HashSet<>();
        for (Component c : comps) s.add(c.name());
        return s;
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        for (String x : a) if (b.contains(x)) return true;
        return false;
    }

    // a rule that implies the two sides communicate (so Isolation over the same pair conflicts)
    private static boolean connects(SecurityRule r) {
        return r instanceof Confidentiality || r instanceof Integrity || r instanceof Authentication;
    }

    /** Component name part of a "Component.port" reference (or the whole string if no dot). */
    private static String componentOf(String portRef) {
        int dot = portRef.indexOf('.');
        return dot < 0 ? portRef : portRef.substring(0, dot);
    }

    /**
     * BFS over the component-adjacency graph (two components adjacent iff they share a
     * connector), starting from any component in {@code from}, refusing to enter any
     * component in {@code avoid}. Returns the component path reached (as names, from -> ...
     * -> target) the first time a {@code to} component is reached, or null if none is
     * reachable without passing through {@code avoid}.
     */
    private static List<String> pathAvoiding(Set<String> from, Set<String> to, Set<String> avoid,
                                              Map<String, Set<String>> componentConnectors,
                                              Map<String, Set<String>> connectorComponents) {
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (String s : from) {
            if (avoid.contains(s)) continue;
            if (visited.add(s)) queue.add(s);
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (to.contains(cur) && !from.contains(cur)) return buildPath(parent, cur);
            for (String conn : componentConnectors.getOrDefault(cur, Set.of())) {
                for (String nbr : connectorComponents.getOrDefault(conn, Set.of())) {
                    if (nbr.equals(cur) || avoid.contains(nbr) || visited.contains(nbr)) continue;
                    visited.add(nbr);
                    parent.put(nbr, cur);
                    if (to.contains(nbr)) return buildPath(parent, nbr);
                    queue.add(nbr);
                }
            }
        }
        return null;
    }

    private static List<String> buildPath(Map<String, String> parent, String end) {
        List<String> path = new ArrayList<>();
        String cur = end;
        while (cur != null) { path.add(0, cur); cur = parent.get(cur); }
        return path;
    }

    // replicas per component: its own scale, else its enclosing Colocation's (null = none, so 1)
    private static Map<String, Integer> effectiveReplicas(Architecture arch) {
        Map<String, Integer> out = new HashMap<>();
        walkReplicas(arch.components(), null, out);
        return out;
    }

    private static void walkReplicas(List<Component> comps, Integer enclosingColo,
                                     Map<String, Integer> out) {
        for (Component c : comps) {
            Integer own = replicasOf(c);
            out.put(c.name(), own != null ? own : enclosingColo);
            // a Colocation scales as one unit -> its members inherit its replica count
            Integer childCtx = c.type().equals("Colocation") && own != null ? own : enclosingColo;
            walkReplicas(c.members(), childCtx, out);
        }
    }

    private static Integer replicasOf(Component c) {
        if (c.properties().get("scale") instanceof Map<?, ?> sm) {
            Object r = sm.get("replicas");
            if (r instanceof Number n) return n.intValue();
            if (r != null) try { return Integer.valueOf(r.toString()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // spread per component: its own, else its enclosing Colocation's
    private static Map<String, String> effectiveSpread(Architecture arch) {
        Map<String, String> out = new HashMap<>();
        walkSpread(arch.components(), null, out);
        return out;
    }

    private static void walkSpread(List<Component> comps, String enclosingColo,
                                   Map<String, String> out) {
        for (Component c : comps) {
            String own = spreadOf(c);
            out.put(c.name(), own != null ? own : enclosingColo);
            String childCtx = c.type().equals("Colocation") && own != null ? own : enclosingColo;
            walkSpread(c.members(), childCtx, out);
        }
    }

    private static String spreadOf(Component c) {
        Object s = c.properties().get("spread");
        return s == null ? null : s.toString();
    }

    /** True if the rule has a tctx argument that was actually given (not null). */
    private static boolean tctxSpecified(SecurityRule rule) {
        return switch (rule) {
            case Confidentiality r -> r.tctx() != null;
            case Integrity r       -> r.tctx() != null;
            case Isolation r       -> r.tctx() != null;
            case Authentication r  -> r.tctx() != null;
            case Authorization r   -> true;   // resource maps to tctx and is always required
            case Availability r    -> false;  // single context (target -> sctx); no tctx side
        };
    }
}
