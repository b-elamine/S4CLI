package sam4c.light.web;

import sam4c.light.metamodel.MClass;
import sam4c.light.metamodel.Sam4cMetamodel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the editor palette JSON from the M2 metamodel.
 *
 * The palette is derived, not hard-coded: every concrete subtype of Component
 * becomes a component item, and Connector becomes a connector item. Adding a new
 * type to ArchMetamodel automatically adds a palette item -- the metamodel drives
 * the editor. Required attributes per type come from AttributePolicy.
 */
public final class Palette {

    private Palette() {}

    public static String json() {
        var mm = Sam4cMetamodel.INSTANCE;
        List<String> items = new ArrayList<>();

        for (MClass cls : Sam4cMetamodel.ARCH.classes()) {
            if (cls.abstractClass()) continue;
            String type = cls.name();

            if (mm.isA(type, "Component")) {
                // Colocation is the only container left (it holds member Deployables);
                // a Deployable's placement on a Host is a reference, not containment.
                boolean container = mm.isA(type, "Colocation");
                String req = AttributePolicy.requiredFor(type).stream()
                    .map(a -> "\"" + a + "\"").collect(Collectors.joining(", "));
                items.add(String.format(
                    "{ \"type\":\"%s\", \"kind\":\"component\", \"container\":%s, \"requiredAttrs\":[%s] }",
                    type, container, req));
            } else if (type.equals("Connector")) {
                items.add("{ \"type\":\"Connector\", \"kind\":\"connector\", \"container\":false, \"requiredAttrs\":[] }");
            }
        }

        return "[" + String.join(", ", items) + "]";
    }
}
