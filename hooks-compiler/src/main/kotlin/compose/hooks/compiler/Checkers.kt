package compose.hooks.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirSamConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName

class HooksCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> =
            setOf(RulesOfHooksChecker, MissingProviderChecker, LendingLeakChecker)
    }
}

internal val HOOKS = FqName("compose.hooks")
internal val QUERY = FqName("compose.hooks.query")
internal val COMPOSE_RUNTIME = FqName("androidx.compose.runtime")

/** Hooks that take a slot in the active customRemember scope (position-dependent). */
internal val SLOT_HOOKS = mapOf(
    HOOKS to setOf(
        "rememberState", "rememberEffect", "rememberReducer", "rememberMemo", "rememberRef", "rememberUpdated",
        "rememberFlow", "rememberId", "rememberContext", "Provider",
    ),
    QUERY to setOf("rememberAtom", "rememberGlobalState", "rememberParentState", "ParentState"),
)

/** Inline higher-order functions whose lambda runs repeatedly. */
private val LOOP_LAMBDAS = setOf(
    "forEach", "forEachIndexed", "repeat", "map", "mapIndexed", "mapNotNull", "flatMap", "filter", "onEach",
    "associate", "associateWith", "associateBy", "fold", "sumOf", "all", "any", "none", "count",
)

internal fun FirElement.unwrapLambda(): FirAnonymousFunction? = when (this) {
    is FirAnonymousFunctionExpression -> anonymousFunction
    is FirAnonymousFunction -> this
    is FirSamConversionExpression -> expression.unwrapLambda()
    is FirWrappedArgumentExpression -> expression.unwrapLambda()
    else -> null
}

internal fun FirFunctionCall.callableId(): CallableId? = calleeReference.toResolvedCallableSymbol()?.callableId

private fun CallableId.isSlotHook() = SLOT_HOOKS[packageName]?.contains(callableName.asString()) == true
private fun CallableId.isCustomRemember() = packageName == HOOKS && callableName.asString() == "customRemember"
private fun CallableId.isContextFactory() = packageName == HOOKS && callableName.asString() == "rememberContextOf"
private fun CallableId.isKey() = packageName == COMPOSE_RUNTIME && callableName.asString() == "key"

/**
 * E1 rules of hooks. Walks from the call up to the nearest enclosing customRemember block:
 * - slot hook under if/when branch, loop, try, or a repeating lambda (forEach/repeat/...) -> error (key() does not
 *   help: the slots still belong to the same scope);
 * - a custom hook named remember* (its own child scope) in a loop without key() -> warning (positional identity).
 * Calls outside customRemember are not checked: a bare hook gets its own implicit scope per call site.
 */
object RulesOfHooksChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    override fun check(expression: FirFunctionCall, context: CheckerContext, reporter: DiagnosticReporter) {
        val id = expression.callableId() ?: return
        val name = id.callableName.asString()
        val slot = id.isSlotHook()
        val customHook = !slot && name.startsWith("remember") && !id.isCustomRemember() &&
            !id.isContextFactory() && // rememberContextOf creates a context object; it takes no slot
            id.packageName != COMPOSE_RUNTIME // compose's own remember{} is positional by design
        if (!slot && !customHook) return

        val chain = context.containingElements
        if (System.getProperty("hooks.debug") != null) System.err.println("HOOKSDBG $name " + chain.joinToString(" > ") { it.javaClass.simpleName })
        var conditional = false
        var loop = false
        var keyed = false
        var below: FirElement = expression
        var insideCustomRemember = false
        var i = chain.size - 1
        while (i >= 0) {
            val e = chain[i]
            when (e) {
                is FirWhenBranch -> if (below === e.result) conditional = true
                is FirLoop -> if (below === e.block) loop = true
                is FirTryExpression -> if (below === e.tryBlock) conditional = true
                is FirSimpleFunction -> break
                is FirAnonymousFunction -> {
                    // owner call of this lambda: the nearest FirFunctionCall above that takes it as an argument
                    var j = i - 1
                    var owner: FirFunctionCall? = null
                    while (j >= 0) {
                        val c = chain[j]
                        if (c is FirFunctionCall) {
                            if (c.arguments.any { a -> a.unwrapLambda() === e }) owner = c
                            break
                        }
                        if (c !is FirAnonymousFunctionExpression && c !is FirArgumentList) break
                        j--
                    }
                    val ownerId = owner?.callableId() ?: break
                    when {
                        ownerId.isCustomRemember() -> { insideCustomRemember = true; break }
                        ownerId.isKey() -> keyed = true
                        ownerId.callableName.asString() in LOOP_LAMBDAS -> loop = true
                        else -> break
                    }
                }
                else -> {}
            }
            below = e
            i--
        }
        if (!insideCustomRemember) return
        if (slot && (conditional || loop)) {
            reporter.reportOn(expression.source, HooksErrors.HOOK_CALLED_CONDITIONALLY, name, context)
        } else if (customHook && loop && !keyed) {
            reporter.reportOn(expression.source, HooksErrors.HOOK_IN_LOOP_WITHOUT_KEY, name, context)
        }
    }
}

private fun FirFunctionCall.isRememberContext(): Boolean {
    val id = callableId() ?: return false
    return (id.packageName == HOOKS && id.callableName.asString() == "rememberContext") ||
        (id.packageName == QUERY && id.callableName.asString() == "rememberParentState")
}

/**
 * E2 (partial, same file): `rememberContext(Ctx)` where `Ctx` is a private top-level property of this file,
 * initialised by `rememberContextOf(...)` without a default, and no `Ctx.Provider(...)` appears in this file.
 */
object MissingProviderChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    @OptIn(SymbolInternals::class)
    override fun check(expression: FirFunctionCall, context: CheckerContext, reporter: DiagnosticReporter) {
        val id = expression.callableId() ?: return
        if (id.packageName != HOOKS || id.callableName.asString() != "rememberContext") return
        val arg = expression.arguments.firstOrNull() as? FirPropertyAccessExpression ?: return
        val prop = arg.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return
        if (prop.isLocal) return
        val file = context.containingFile ?: return
        val decl = file.declarations.filterIsInstance<FirProperty>().firstOrNull { it.symbol == prop } ?: return
        if (decl.visibility != Visibilities.Private) return
        val init = decl.initializer as? FirFunctionCall ?: return
        val initId = init.callableId() ?: return
        if (initId.packageName != HOOKS || initId.callableName.asString() != "rememberContextOf") return
        if (init.arguments.size >= 2) return // a default is given
        if (providedIn(file).contains(prop)) return
        reporter.reportOn(expression.source, HooksErrors.CONTEXT_WITHOUT_PROVIDER, prop.name.asString(), context)
    }

    private val cache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<FirFile, Set<FirPropertySymbol>>())

    private fun providedIn(file: FirFile): Set<FirPropertySymbol> = cache.getOrPut(file) {
        val out = HashSet<FirPropertySymbol>()
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirFunctionCall) {
                    val id = element.callableId()
                    if (id != null && id.packageName == HOOKS && id.callableName.asString() == "Provider") {
                        val receiver = element.extensionReceiver as? FirQualifiedAccessExpression
                        (receiver?.calleeReference?.toResolvedCallableSymbol() as? FirPropertySymbol)?.let(out::add)
                    }
                }
                element.acceptChildren(this)
            }
        })
        out
    }
}

/**
 * B5: a local initialised from rememberContext/rememberParentState read inside the lambda of
 * GlobalScope.launch/async, thread { } or Thread { }.
 */
object LendingLeakChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    @OptIn(SymbolInternals::class)
    override fun check(expression: FirFunctionCall, context: CheckerContext, reporter: DiagnosticReporter) {
        val sym = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val id = sym.callableId
        val name = id.callableName.asString()
        val escaping = when {
            (name == "launch" || name == "async") && id.packageName == FqName("kotlinx.coroutines") -> {
                val r = expression.explicitReceiver
                r is FirResolvedQualifier && r.classId?.asSingleFqName() == FqName("kotlinx.coroutines.GlobalScope")
            }
            name == "thread" && id.packageName == FqName("kotlin.concurrent") -> true
            sym is FirConstructorSymbol && sym.callableId.className?.asString() == "Thread" &&
                id.packageName == FqName("java.lang") -> true
            else -> false
        }
        if (!escaping) return
        for (arg in expression.arguments) {
            val lambda = arg.unwrapLambda() ?: continue
            lambda.accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirPropertyAccessExpression) {
                        val p = element.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol
                        if (p != null && p.isLocal) {
                            val init = p.fir.initializer as? FirFunctionCall
                            if (init != null && init.isRememberContext()) {
                                reporter.reportOn(element.source, HooksErrors.LENT_CONTEXT_ESCAPES, p.name.asString(), context)
                            }
                        }
                    }
                    element.acceptChildren(this)
                }
            })
        }
    }
}
