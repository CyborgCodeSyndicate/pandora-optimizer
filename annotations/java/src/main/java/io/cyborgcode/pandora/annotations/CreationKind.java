package io.cyborgcode.pandora.annotations;

/**
 * How callers obtain an instance of the annotated API type.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public enum CreationKind {
   AUTO,
   CONSTRUCTOR,
   BUILDER,
   STATIC_FACTORY,
   ENUM_CONSTANT,
   SINGLETON,
   PROVIDED
}
