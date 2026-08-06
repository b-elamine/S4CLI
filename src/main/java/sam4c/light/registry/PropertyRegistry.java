package sam4c.light.registry;

import sam4c.light.model.ref.Ref;
import sam4c.light.model.ref.NamedRef;
import sam4c.light.model.rule.*;

import java.util.*;

public class PropertyRegistry {

    private final Map<String, RuleFactory> factories = new LinkedHashMap<>();

    public static PropertyRegistry withDefaults() {
        PropertyRegistry r = new PropertyRegistry();
        r.register(new RuleFactory() {
            public String keyword() { return "Confidentiality"; }
            public SecurityRule create(List<Ref> args, Ref ret) {
                return new Confidentiality(require(args, 0, "Confidentiality"), get(args, 1));
            }
        });
        r.register(new RuleFactory() {
            public String keyword() { return "Integrity"; }
            public SecurityRule create(List<Ref> args, Ref ret) {
                return new Integrity(require(args, 0, "Integrity"), get(args, 1));
            }
        });
        r.register(new RuleFactory() {
            public String keyword() { return "Isolation"; }
            public SecurityRule create(List<Ref> args, Ref ret) {
                // Isolation(sctx, tctx [, via]) -- via is the optional approved-mediator context.
                return new Isolation(require(args, 0, "Isolation"), get(args, 1), get(args, 2));
            }
        });
        r.register(new RuleFactory() {
            public String keyword() { return "Authentication"; }
            public SecurityRule create(List<Ref> args, Ref ret) {
                return new Authentication(
                        require(args, 0, "Authentication"),
                        require(args, 1, "Authentication"),
                        Objects.requireNonNull(ret, "Authentication requires a -> target"));
            }
        });
        r.register(new RuleFactory() {
            public String keyword() { return "Authorization"; }
            public SecurityRule create(List<Ref> args, Ref ret) {
                // Authorization(subject, resource, action [, action ...]) -- arg0=subject,
                // arg1=resource, the rest are action names (a role may grant several verbs).
                require(args, 1, "Authorization");
                require(args, 2, "Authorization");
                List<String> actions = new ArrayList<>();
                for (int i = 2; i < args.size(); i++) {
                    Ref a = args.get(i);
                    actions.add(a instanceof NamedRef nr ? nr.name() : a.toString());
                }
                return new Authorization(args.get(0), args.get(1), actions);
            }
        });
        r.register(new RuleFactory() {
            public String keyword() { return "Availability"; }
            public SecurityRule create(List<Ref> args, Ref ret) {
                // Availability(target, level) -- arg0=target context, arg1=level token
                // (high|medium|low), read as a name like Authorization's action tokens.
                require(args, 0, "Availability");
                Ref lvl = require(args, 1, "Availability");
                String level = lvl instanceof NamedRef nr ? nr.name() : lvl.toString();
                return new Availability(args.get(0), level);
            }
        });
        return r;
    }

    public void register(RuleFactory factory) {
        factories.put(factory.keyword(), factory);
    }

    public SecurityRule create(String keyword, List<Ref> args, Ref returnRef) {
        RuleFactory factory = factories.get(keyword);
        if (factory == null)
            throw new IllegalArgumentException(
                    "Unknown property '" + keyword + "'. Known: " + factories.keySet());
        return factory.create(args, returnRef);
    }

    public Set<String> keywords() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    private static Ref require(List<Ref> args, int i, String keyword) {
        if (i >= args.size())
            throw new IllegalArgumentException(keyword + ": missing argument at position " + i);
        return args.get(i);
    }

    private static Ref get(List<Ref> args, int i) {
        return i < args.size() ? args.get(i) : null;
    }
}
