package dev.ide.android.support.viewbinding

/**
 * Renders a [BindingClass] as compilable Java source — the real artifact the build emits into its generated
 * sources, the runtime counterpart of the editor's synthetic class (same name/package/fields, so a layout
 * resolved in the editor builds identically). Everything is fully qualified (no imports), so it compiles in
 * isolation; the only external types it needs are `androidx.viewbinding.ViewBinding` (the runtime the build
 * adds when ViewBinding is enabled) and the module's `R`.
 *
 * Mirrors AGP's generator: a private final root, a public final field per id, a private constructor, the two
 * `inflate` overloads, `bind`, and a covariant `getRoot()`. `bind` throws on a required view that is missing
 * (a layout-config mismatch), exactly like AGP's null check.
 */
object ViewBindingJavaSource {

    /** [rPackage] is the package of the module's `R` class (its namespace), e.g. `com.example.app`. */
    fun emit(b: BindingClass, rPackage: String): String {
        val r = if (rPackage.isBlank()) "R" else "$rPackage.R"
        val sb = StringBuilder()
        if (b.packageName.isNotEmpty()) sb.append("package ").append(b.packageName).append(";\n\n")
        sb.append("public final class ").append(b.simpleName).append(" implements androidx.viewbinding.ViewBinding {\n")

        sb.append("  private final ").append(b.rootViewType).append(" rootView;\n")
        for (f in b.fields) sb.append("  public final ").append(f.viewType).append(' ').append(f.name).append(";\n")
        sb.append('\n')

        // Private constructor: root + every field, in declaration order.
        sb.append("  private ").append(b.simpleName).append('(')
        sb.append((listOf("${b.rootViewType} rootView") + b.fields.map { "${it.viewType} ${it.name}" }).joinToString(", "))
        sb.append(") {\n")
        sb.append("    this.rootView = rootView;\n")
        for (f in b.fields) sb.append("    this.").append(f.name).append(" = ").append(f.name).append(";\n")
        sb.append("  }\n\n")

        // getRoot (covariant override of ViewBinding.getRoot()).
        sb.append("  @java.lang.Override\n")
        sb.append("  public ").append(b.rootViewType).append(" getRoot() { return rootView; }\n\n")

        // inflate(LayoutInflater) -> inflate(inflater, null, false).
        sb.append("  public static ").append(b.simpleName)
            .append(" inflate(android.view.LayoutInflater inflater) {\n")
        sb.append("    return inflate(inflater, null, false);\n")
        sb.append("  }\n\n")

        // inflate(LayoutInflater, ViewGroup, boolean): inflate the layout, optionally attach, then bind.
        sb.append("  public static ").append(b.simpleName)
            .append(" inflate(android.view.LayoutInflater inflater, android.view.ViewGroup parent, boolean attachToParent) {\n")
        sb.append("    android.view.View root = inflater.inflate(").append(r).append(".layout.").append(b.layoutName)
            .append(", parent, false);\n")
        sb.append("    if (attachToParent) { parent.addView(root); }\n")
        sb.append("    return bind(root);\n")
        sb.append("  }\n\n")

        // bind(View): find each view by id (throwing if a required one is absent), then construct.
        sb.append("  public static ").append(b.simpleName).append(" bind(android.view.View rootView) {\n")
        for (f in b.fields) emitBindField(sb, f, r)
        val args = (listOf("(${b.rootViewType}) rootView") + b.fields.map { it.name }).joinToString(", ")
        sb.append("    return new ").append(b.simpleName).append('(').append(args).append(");\n")
        sb.append("  }\n")

        sb.append("}\n")
        return sb.toString()
    }

    private fun emitBindField(sb: StringBuilder, f: BindingField, r: String) {
        val idRef = "$r.id.${f.resId}"
        when (f.kind) {
            BindingFieldKind.VIEW -> {
                // View.findViewById is generic (<T extends View> T), so the assignment needs no cast.
                sb.append("    ").append(f.viewType).append(' ').append(f.name)
                    .append(" = rootView.findViewById(").append(idRef).append(");\n")
                sb.append("    if (").append(f.name).append(" == null) { throw new java.lang.NullPointerException(\"Missing required view: ")
                    .append(f.resId).append("\"); }\n")
            }
            BindingFieldKind.BINDING -> {
                // An <include>: locate the included root view, then bind it with its own generated binding.
                val viewVar = f.name + "View"
                sb.append("    android.view.View ").append(viewVar)
                    .append(" = rootView.findViewById(").append(idRef).append(");\n")
                sb.append("    if (").append(viewVar).append(" == null) { throw new java.lang.NullPointerException(\"Missing required view: ")
                    .append(f.resId).append("\"); }\n")
                sb.append("    ").append(f.viewType).append(' ').append(f.name)
                    .append(" = ").append(f.viewType).append(".bind(").append(viewVar).append(");\n")
            }
        }
    }
}
