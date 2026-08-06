package sam4c.light.metamodel;

import static sam4c.light.metamodel.MDataType.STRING;

/**
 * Core sub-metamodel.
 * Defines the shared base type used by both the architecture and security
 * metamodels. Equivalent to the "core" subpackage in sam4c.ecore.
 *
 *   ContextualElement -- anything with a name that can appear as a context
 *                        reference in security rules (the bridge between
 *                        architecture and security layers)
 */
public final class CoreMetamodel {

    public static final MPackage INSTANCE = define();

    private CoreMetamodel() {}

    private static MPackage define() {
        return new MPackage("core", "http://avalon.inria.fr/sam4c/core/", java.util.List.of(

            MClass.builder("ContextualElement").abstractClass()
                .attr("name", STRING, 0, 1)
                .build()
        ));
    }
}
