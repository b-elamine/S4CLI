package sam4c.light.output;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import sam4c.light.model.*;
import sam4c.light.model.ref.Ref;
import sam4c.light.model.rule.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ModelSerializer {

    private static final ObjectMapper JSON = buildMapper();

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        SimpleModule module = new SimpleModule();
        module.addSerializer(UnifiedModel.class, new UnifiedModelSerializer());
        module.addSerializer(SecurityRule.class, new SecurityRuleSerializer());
        m.registerModule(module);
        return m;
    }

    public static void write(UnifiedModel model, File output) throws IOException {
        JSON.writeValue(output, model);
    }

    public static String toJson(UnifiedModel model) throws IOException {
        return JSON.writeValueAsString(model);
    }

    // -------------------------------------------------------------------------
    // Serializer
    //
    // The output is designed to serve two readers at the same time:
    //
    //   Human:     open the file, read resolvedRules, see exactly which
    //              components each security rule governs and all their details.
    //
    //   Generator: read resolvedRules, iterate sctx/tctx/actx, access component
    //              name/type/attributes/ports directly -- no lookup, no join,
    //              nothing else needed from the file.
    //
    // Structure:
    //   architecture  -- full component tree (canonical structural view)
    //   security      -- raw DSL model (attributes, contexts, rules as parsed)
    //   coverage      -- for each named context: the full component objects it matched
    //   resolvedRules -- each rule with full inline component objects for sctx/tctx/actx
    //   unresolved    -- names that could not be resolved (only if non-empty)
    // -------------------------------------------------------------------------

    private static class UnifiedModelSerializer extends StdSerializer<UnifiedModel> {

        UnifiedModelSerializer() { super(UnifiedModel.class); }

        @Override
        public void serialize(UnifiedModel model, JsonGenerator g, SerializerProvider p)
                throws IOException {

            g.writeStartObject();

            g.writeStringField("name", model.architecture().name());

            // Full architecture tree -- for structural understanding
            g.writeFieldName("architecture");
            p.defaultSerializeValue(model.architecture(), g);

            // Raw security declarations -- attributes, contexts, rules as parsed
            g.writeFieldName("security");
            p.defaultSerializeValue(model.security(), g);

            // Coverage: each context name -> full component objects that matched it
            g.writeFieldName("coverage");
            g.writeStartObject();
            for (Map.Entry<String, List<Component>> entry : model.coverage().entrySet()) {
                g.writeFieldName(entry.getKey());
                writeComponentArray(g, entry.getValue());
            }
            g.writeEndObject();

            // Resolved rules: every rule with full inline component data.
            // This section alone is sufficient for a generator -- it needs nothing else.
            g.writeFieldName("resolvedRules");
            g.writeStartArray();
            for (ResolvedRule rr : model.resolvedRules()) {
                g.writeStartObject();
                g.writeStringField("type", rr.rule().getClass().getSimpleName());
                writeComponentArray(g, "sctx", rr.sctxComponents());
                if (!rr.tctxComponents().isEmpty())
                    writeComponentArray(g, "tctx", rr.tctxComponents());
                if (!rr.actxComponents().isEmpty())
                    writeComponentArray(g, "actx", rr.actxComponents());
                // Resolved network paths: the connector(s) and directed links that
                // carry traffic between sctx and tctx -- where a generator places controls.
                if (!rr.paths().isEmpty()) {
                    g.writeFieldName("paths");
                    g.writeStartArray();
                    for (ResolvedPath path : rr.paths()) {
                        g.writeStartObject();
                        g.writeStringField("connector", path.connector());
                        writeLinkArray(g, "sctxLinks", path.sctxLinks());
                        writeLinkArray(g, "tctxLinks", path.tctxLinks());
                        g.writeEndObject();
                    }
                    g.writeEndArray();
                }
                g.writeEndObject();
            }
            g.writeEndArray();

            if (!model.unresolved().isEmpty()) {
                g.writeFieldName("unresolved");
                p.defaultSerializeValue(model.unresolved(), g);
            }

            g.writeEndObject();
        }

        // Writes a named field containing an array of full component objects
        private void writeComponentArray(JsonGenerator g, String field, List<Component> comps)
                throws IOException {
            g.writeFieldName(field);
            writeComponentArray(g, comps);
        }

        // Writes a named array of links as {port, direction} objects
        private void writeLinkArray(JsonGenerator g, String field, List<Link> links)
                throws IOException {
            g.writeFieldName(field);
            g.writeStartArray();
            for (Link l : links) {
                g.writeStartObject();
                g.writeStringField("port", l.portRef());
                g.writeStringField("direction", l.direction().token());
                g.writeEndObject();
            }
            g.writeEndArray();
        }

        // Writes an array of full component objects (no field name wrapper)
        private void writeComponentArray(JsonGenerator g, List<Component> comps)
                throws IOException {
            g.writeStartArray();
            for (Component c : comps) writeComponent(g, c);
            g.writeEndArray();
        }

        // Writes one component as a full inline object -- called from coverage and resolvedRules
        private void writeComponent(JsonGenerator g, Component c) throws IOException {
            g.writeStartObject();
            g.writeStringField("name", c.name());
            g.writeStringField("type", c.type());

            // Attributes -- what context predicates are evaluated against
            if (!c.attributes().isEmpty()) {
                g.writeFieldName("attributes");
                g.writeStartObject();
                for (Map.Entry<String, String> e : c.attributes().entrySet())
                    g.writeStringField(e.getKey(), e.getValue());
                g.writeEndObject();
            }

            // Ports -- connection points, needed by generators to emit port-level rules
            if (!c.ports().isEmpty()) {
                g.writeFieldName("ports");
                g.writeStartArray();
                for (Port p : c.ports()) g.writeString(p.name());
                g.writeEndArray();
            }

            // Member names -- for structural reference (full member data is in the
            // architecture section and also appears separately in sctx/tctx if matched)
            if (!c.members().isEmpty()) {
                g.writeFieldName("members");
                g.writeStartArray();
                for (Component member : c.members()) g.writeString(member.name());
                g.writeEndArray();
            }

            g.writeEndObject();
        }
    }

    // -------------------------------------------------------------------------
    // SecurityRule serializer -- writes the rule type name + its Ref fields
    // -------------------------------------------------------------------------

    private static class SecurityRuleSerializer extends StdSerializer<SecurityRule> {

        SecurityRuleSerializer() { super(SecurityRule.class); }

        @Override
        public void serialize(SecurityRule rule, JsonGenerator g, SerializerProvider p)
                throws IOException {
            g.writeStartObject();
            g.writeStringField("type", rule.getClass().getSimpleName());
            switch (rule) {
                case Confidentiality r -> {
                    writeRef(g, p, "sctx", r.sctx());
                    if (r.tctx() != null) writeRef(g, p, "tctx", r.tctx());
                }
                case Integrity r -> {
                    writeRef(g, p, "sctx", r.sctx());
                    if (r.tctx() != null) writeRef(g, p, "tctx", r.tctx());
                }
                case Isolation r -> {
                    writeRef(g, p, "sctx", r.sctx());
                    if (r.tctx() != null) writeRef(g, p, "tctx", r.tctx());
                }
                case Authentication r -> {
                    writeRef(g, p, "sctx", r.sctx());
                    writeRef(g, p, "actx", r.actx());
                    writeRef(g, p, "tctx", r.tctx());
                }
                case Authorization r -> {
                    writeRef(g, p, "subject", r.subject());
                    writeRef(g, p, "resource", r.resource());
                    g.writeFieldName("actions");
                    g.writeStartArray();
                    for (String a : r.actions()) g.writeString(a);
                    g.writeEndArray();
                }
                case Availability r -> {
                    writeRef(g, p, "target", r.target());
                    g.writeStringField("level", r.level());
                }
            }
            g.writeEndObject();
        }

        private void writeRef(JsonGenerator g, SerializerProvider p, String field, Ref ref)
                throws IOException {
            g.writeFieldName(field);
            p.defaultSerializeValue(ref, g);
        }
    }
}
