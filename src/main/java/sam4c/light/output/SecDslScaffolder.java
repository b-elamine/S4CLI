package sam4c.light.output;

import sam4c.light.model.Architecture;
import sam4c.light.model.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Generates a starter .secdsl file from an architecture.
 *
 * The architecture already contains every attribute key and value (in each
 * component's attributes map). This scaffolder scans them and emits:
 *
 *   - one #attribute declaration per distinct key, listing all values seen
 *   - one #context per (key=value) pair, with a derived name like frontendCtx
 *   - a commented-out template of the four property types for the user to fill
 *
 * It never invents security rules -- those require human intent. It only
 * pre-fills the declarative part (attributes + contexts) that can be derived
 * mechanically from the architecture, so the user starts from a working
 * skeleton instead of a blank file.
 */
public class SecDslScaffolder {

    public static void write(Architecture arch, File output) throws IOException {
        Files.writeString(output.toPath(), generate(arch));
    }

    /** Produce the starter .secdsl content as a string (used by file write and the web server). */
    public static String generate(Architecture arch) {
        // Collect attribute key -> ordered set of values across all components
        Map<String, LinkedHashSet<String>> attrValues = new LinkedHashMap<>();
        collect(arch.components(), attrValues);

        StringBuilder sb = new StringBuilder();
        sb.append("// Auto-generated starter security model for: ").append(arch.name()).append("\n");
        sb.append("// Attributes and contexts below were derived from the architecture file.\n");
        sb.append("// Uncomment and edit the #property lines to declare your security rules.\n\n");

        if (attrValues.isEmpty()) {
            sb.append("// No attributes found on any component in the architecture.\n");
            sb.append("// Add `attributes:` blocks to your components first, then re-run.\n");
            return sb.toString();
        }

        // 1. Attribute declarations
        sb.append("// ---- Attributes (one value list per key found in the architecture) ----\n");
        attrValues.forEach((key, values) ->
            sb.append("#attribute ").append(key)
              .append(" = (").append(String.join(", ", values)).append(");\n"));
        sb.append("\n");

        // 2. Context declarations -- one per (key=value) pair
        sb.append("// ---- Contexts (one per attribute value -- rename or combine as needed) ----\n");
        attrValues.forEach((key, values) -> {
            for (String value : values) {
                sb.append("#context ").append(contextName(value))
                  .append(" = (").append(key).append("=").append(value).append(");\n");
            }
        });
        sb.append("\n");

        // 3. Property templates -- commented out, for the user to fill in
        sb.append("// ---- Security rules (uncomment and edit) ----\n");
        sb.append("// #property Confidentiality(sourceCtx, targetCtx);\n");
        sb.append("// #property Integrity(sourceCtx, targetCtx);\n");
        sb.append("// #property Isolation(sourceCtx, targetCtx);\n");
        sb.append("// #property Authentication(whoCtx, mechanismCtx) -> targetCtx;\n");
        sb.append("// #property Authorization(subjectCtx, resourceCtx, action [, action ...]);\n");
        sb.append("// #property Availability(targetCtx, high | medium | low);\n");

        return sb.toString();
    }

    /** Recursively walk the component tree collecting attribute keys and values. */
    private static void collect(List<Component> components,
                                Map<String, LinkedHashSet<String>> attrValues) {
        for (Component c : components) {
            c.attributes().forEach((key, value) ->
                attrValues.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(value));
            if (!c.members().isEmpty()) {
                collect(c.members(), attrValues);
            }
        }
    }

    /** Derive a context name from a value, e.g. "frontend" -> "frontendCtx". */
    private static String contextName(String value) {
        String cleaned = value.replaceAll("[^a-zA-Z0-9]", "");
        if (cleaned.isEmpty()) cleaned = "value";
        return cleaned + "Ctx";
    }
}
