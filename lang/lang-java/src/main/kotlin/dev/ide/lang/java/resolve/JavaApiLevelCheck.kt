package dev.ide.lang.java.resolve

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.util.TypeConversionUtil
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.ApiGuards
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.ApiPlatform
import dev.ide.lang.resolve.PlatformApiLevels
import dev.ide.platform.EngineCancellation
import dev.ide.psi.IntellijPsiHost

/**
 * The API-level check. On an Android module it is the minSdk check (Android lint's `NewApi`): a call,
 * constructor or field read of a platform API added after the module's `minSdk` is reported unless something
 * guarantees the level where it runs:
 *  - an enclosing `if`/`?:`/`while`/`&&` on `Build.VERSION.SDK_INT` ([ApiGuards.levelGuaranteed]);
 *  - an earlier `if (SDK_INT < N) return;` in an enclosing block;
 *  - `@RequiresApi(N)` / `@TargetApi(N)` on an enclosing method or class.
 *
 * Constants are not reported (javac inlines them), nor are the `java.*` methods D8 backports. An API the
 * table does not know is never reported.
 *
 * On a plain JVM module the same walk compares JDK APIs with the module's Java release (`javac --release`),
 * with no guards: nothing at run time makes a newer JDK API available to code compiled for an older release.
 */
internal object JavaApiLevelCheck {

    fun of(psi: PsiJavaFile, api: ApiLevelContext): List<Diagnostic> = IntellijPsiHost.withParseLock {
        val out = ArrayList<Diagnostic>()
        psi.accept(object : JavaRecursiveElementVisitor() {
            private var seen = 0

            override fun visitElement(element: PsiElement) {
                if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
                super.visitElement(element)
            }

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val anchor = expression.methodExpression.referenceNameElement ?: return
                val method = expression.resolveMethod() ?: return
                report(anchor, "Call", method, api, out)
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val reference = expression.classOrAnonymousClassReference ?: return
                val ctor = expression.resolveConstructor()
                if (ctor != null) {
                    report(reference, "Call", ctor, api, out)
                } else {
                    val cls = reference.resolve() as? PsiClass ?: return
                    val name = binaryName(cls) ?: return
                    val level = api.levels.classLevel(name) ?: return
                    reportIfTooNew(reference, "Call", "new ${cls.qualifiedName}", level, api, out)
                }
            }

            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                if ((expression.parent as? PsiMethodCallExpression)?.methodExpression === expression) return
                val field = expression.resolve() as? PsiField ?: return
                // A constant is inlined by javac, so reading one never touches the platform at run time.
                if (field.hasModifierProperty(PsiModifier.FINAL) && field.computeConstantValue() != null) return
                val owner = field.containingClass ?: return
                val ownerName = binaryName(owner) ?: return
                val level = api.levels.fieldLevel(ownerName, field.name) ?: return
                val anchor = expression.referenceNameElement ?: expression
                reportIfTooNew(anchor, "Field", "${owner.qualifiedName}#${field.name}", level, api, out)
            }
        })
        out
    }

    /** The platform level of a resolved method, field or class (the doc popup's "Added in API N"), or null. */
    fun levelOf(target: PsiElement, levels: PlatformApiLevels): ApiLevel? = when (target) {
        is PsiMethod -> methodLevel(target, levels)
        is PsiField -> target.containingClass?.let(::binaryName)?.let { levels.fieldLevel(it, target.name) }
        is PsiClass -> binaryName(target)?.let(levels::classLevel)
        else -> null
    }

    private fun methodLevel(method: PsiMethod, levels: PlatformApiLevels): ApiLevel? {
        val ownerName = method.containingClass?.let(::binaryName) ?: return null
        val name = if (method.isConstructor) "<init>" else method.name
        return descriptorOf(method)?.let { levels.methodLevel(ownerName, name, it) }
            ?: levels.methodLevel(ownerName, name, null, method.parameterList.parametersCount)
    }

    private fun report(anchor: PsiElement, what: String, method: PsiMethod, api: ApiLevelContext, out: MutableList<Diagnostic>) {
        val owner = method.containingClass ?: return
        val level = methodLevel(method, api.levels) ?: return
        val label = if (method.isConstructor) "new ${owner.qualifiedName}" else "${owner.qualifiedName}#${method.name}"
        reportIfTooNew(anchor, what, label, level, api, out)
    }

    private fun reportIfTooNew(
        anchor: PsiElement, what: String, label: String, level: ApiLevel,
        api: ApiLevelContext, out: MutableList<Diagnostic>,
    ) {
        if (level.since <= api.minLevel) return
        if (api.platform == ApiPlatform.ANDROID && (level.desugared || guaranteedLevel(anchor) >= level.since)) return
        val r = anchor.textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), if (api.enforced) Severity.ERROR else Severity.WARNING,
            ApiGuards.message(what, label, level.since, api),
            code = ApiGuards.codeFor(api.platform),
        )
    }

    /** The highest API level the code around [element] guarantees through guards and annotations. */
    internal fun guaranteedLevel(element: PsiElement): Int {
        var level = 0
        var child: PsiElement = element
        var parent: PsiElement? = element.parent
        while (parent != null && parent !is PsiFile) {
            level = maxOf(level, guardFrom(parent, child))
            child = parent
            parent = parent.parent
        }
        return level
    }

    private fun guardFrom(parent: PsiElement, child: PsiElement): Int = when (parent) {
        is PsiIfStatement -> {
            val cond = parent.condition?.text
            when {
                cond == null -> 0
                child === parent.thenBranch -> ApiGuards.levelGuaranteed(cond, true)
                child === parent.elseBranch -> ApiGuards.levelGuaranteed(cond, false)
                else -> 0
            }
        }
        is PsiConditionalExpression -> {
            val cond = parent.condition.text
            when {
                child === parent.thenExpression -> ApiGuards.levelGuaranteed(cond, true)
                child === parent.elseExpression -> ApiGuards.levelGuaranteed(cond, false)
                else -> 0
            }
        }
        is PsiWhileStatement -> {
            val cond = parent.condition?.text
            if (cond != null && child === parent.body) ApiGuards.levelGuaranteed(cond, true) else 0
        }
        is PsiPolyadicExpression -> {
            val outcome = when (parent.operationTokenType) {
                JavaTokenType.ANDAND -> true
                JavaTokenType.OROR -> false
                else -> null
            }
            if (outcome == null) 0
            else parent.operands.takeWhile { it !== child }.maxOfOrNull { ApiGuards.levelGuaranteed(it.text, outcome) } ?: 0
        }
        is PsiCodeBlock -> earlyExitLevel(parent, child)
        is PsiMethod, is PsiClass -> annotationLevel(parent as PsiModifierListOwner)
        else -> 0
    }

    /** `if (SDK_INT < 26) return;` earlier in [block] guarantees 26 for the statements after it. */
    private fun earlyExitLevel(block: PsiCodeBlock, child: PsiElement): Int {
        var level = 0
        for (statement in block.statements) {
            if (statement === child) break
            val ifStatement = statement as? PsiIfStatement ?: continue
            val cond = ifStatement.condition?.text ?: continue
            val then = ifStatement.thenBranch
            val otherwise = ifStatement.elseBranch
            if (otherwise == null && exits(then)) level = maxOf(level, ApiGuards.levelGuaranteed(cond, false))
            else if (otherwise != null && exits(otherwise) && !exits(then)) level = maxOf(level, ApiGuards.levelGuaranteed(cond, true))
        }
        return level
    }

    private fun exits(statement: PsiStatement?): Boolean = when (statement) {
        is PsiReturnStatement, is PsiThrowStatement, is PsiBreakStatement, is PsiContinueStatement -> true
        is PsiBlockStatement -> exits(statement.codeBlock.statements.lastOrNull())
        else -> false
    }

    private fun annotationLevel(owner: PsiModifierListOwner): Int {
        val annotations = owner.modifierList?.annotations ?: return 0
        var level = 0
        for (annotation in annotations) {
            val name = annotation.nameReferenceElement?.referenceName ?: continue
            val value = annotation.parameterList.attributes
                .firstOrNull { it.name == null || it.name == "value" || it.name == "api" }?.value?.text
            level = maxOf(level, ApiGuards.levelFromAnnotation(name, value))
        }
        return level
    }

    /** `android.os.Build$VERSION` for a nested class, so the table (keyed by binary name) finds it. */
    internal fun binaryName(cls: PsiClass): String? {
        val outer = cls.containingClass ?: return cls.qualifiedName
        return binaryName(outer)?.let { "$it\$${cls.name}" }
    }

    /** The erased JVM descriptor (`(ILjava/lang/String;)V`), or null when a type does not resolve. */
    internal fun descriptorOf(method: PsiMethod): String? {
        val sb = StringBuilder("(")
        for (parameter in method.parameterList.parameters) sb.append(typeDescriptor(parameter.type) ?: return null)
        sb.append(')')
        sb.append(if (method.isConstructor) "V" else typeDescriptor(method.returnType ?: return null) ?: return null)
        return sb.toString()
    }

    private fun typeDescriptor(type: PsiType): String? = when (val t = TypeConversionUtil.erasure(type)) {
        is PsiPrimitiveType -> PRIMITIVES[t.canonicalText]
        is PsiArrayType -> typeDescriptor(t.componentType)?.let { "[$it" }
        is PsiClassType -> t.resolve()?.let(::binaryName)?.let { "L${it.replace('.', '/')};" }
        else -> null
    }

    private val PRIMITIVES = mapOf(
        "int" to "I", "long" to "J", "boolean" to "Z", "byte" to "B", "char" to "C",
        "short" to "S", "float" to "F", "double" to "D", "void" to "V",
    )
}
