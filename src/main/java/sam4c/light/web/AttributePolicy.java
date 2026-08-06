package sam4c.light.web;

import sam4c.light.model.Architecture;
import sam4c.light.model.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Editor-level policy for attributes that must be present before an architecture
 * can be turned into files.
 *
 * The M2 metamodel only requires `type` structurally; attributes like Domain are
 * optional there (they are how rules match, not a structural must). This policy is
 * the editor's own rule: it declares which attribute keys are mandatory per type,
 * and the web editor BLOCKS YAML/SecDSL generation until they are filled.
 *
 * It is intentionally separate from the metamodel and ConformanceChecker so it can
 * be tightened or relaxed without touching the model layer.
 */
public final class AttributePolicy {

    private AttributePolicy() {}

    /** Required attribute keys by component type. */
    private static final Map<String, List<String>> REQUIRED = Map.of(
        "VM",   List.of("Domain"),
        "App",  List.of("Domain"),
        "Data", List.of("Domain", "DataClass")
    );

    /** Required attribute keys for a given component type (empty if none). */
    public static List<String> requiredFor(String type) {
        return REQUIRED.getOrDefault(type, List.of());
    }

    /**
     * Validate an architecture against the policy.
     * Returns a list of human-readable problems; empty means it passes.
     */
    public static List<String> validate(Architecture arch) {
        List<String> problems = new ArrayList<>();
        check(arch.components(), problems);
        return problems;
    }

    private static void check(List<Component> components, List<String> problems) {
        for (Component c : components) {
            for (String key : requiredFor(c.type())) {
                String v = c.attributes().get(key);
                if (v == null || v.isBlank())
                    problems.add(c.name() + " (" + c.type() + "): missing required attribute '" + key + "'");
            }
            if (!c.members().isEmpty()) check(c.members(), problems);
        }
    }
}
