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
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirFunction
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
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName

/**
 * The FIR checkers of the plugin: E1 ([RulesOfHooksChecker]), E2 ([MissingProviderChecker]), E3
 * ([UnstableCacheKeyChecker]) and B5 ([LendingLeakChecker]).
 */
class HooksCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    /** Every checker inspects function calls. */
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> =
            setOf(RulesOfHooksChecker, MissingProviderChecker, LendingLeakChecker, UnstableCacheKeyChecker)
    }
}

internal val HOOKS = FqName("compose.hooks")
internal val QUERY = FqName("compose.hooks.query")
internal val COMPOSE_RUNTIME = FqName("androidx.compose.runtime")
internal val HOOKS_TEST = FqName("compose.hooks.test")

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

/**
 * Local variable? A type check rather than `isLocal`, which moved from the symbol to the declaration in Kotlin 2.3.20
 * (one binary for every supported compiler).
 */
internal fun FirPropertySymbol.isLocalProperty(): Boolean = this is FirLocalPropertySymbol

internal fun FirFunctionCall.callableId(): CallableId? = calleeReference.toResolvedCallableSymbol()?.callableId

private fun CallableId.isSlotHook() = SLOT_HOOKS[packageName]?.contains(callableName.asString()) == true
private fun CallableId.isCustomRemember() = packageName == HOOKS && callableName.asString() == "customRemember"
private fun CallableId.isContextFactory() = packageName == HOOKS && callableName.asString() == "rememberContextOf"
private fun CallableId.isKey() = packageName == COMPOSE_RUNTIME && callableName.asString() == "key"

/**
 * E1 rules of hooks. Walks from the call up to the nearest enclosing customRemember block:
 * - slot hook under if/when branch, loop (body or condition), try/catch, or a repeating lambda (forEach/repeat/...) -> error (key() does not
 *   help: the slots still belong to the same scope); also a short-circuit position that is evaluated only sometimes:
 *   the condition of a later `when` / `else if` branch, the right side of `&&` / `||` / `?:`, the selector of `?.`;
 * - a custom hook named remember* (its own child scope) in a loop without key() -> warning (positional identity).
 * Calls outside customRemember are not checked: a bare hook gets its own implicit scope per call site.
 */
object RulesOfHooksChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    /** Reports E1 / E1 warning for this call. */
    override fun check(expression: FirFunctionCall) {
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
                is FirWhenBranch -> {
                    if (below === e.result) conditional = true
                    // a later branch's condition is evaluated only when the earlier ones did not match
                    if (below === e.condition) {
                        val owner = chain.getOrNull(i - 1) as? FirWhenExpression
                        if (owner != null && owner.branches.indexOfFirst { it === e } > 0) conditional = true
                    }
                }
                is FirBooleanOperatorExpression -> if (below === e.rightOperand) conditional = true
                is FirElvisExpression -> if (below === e.rhs) conditional = true
                is FirSafeCallExpression -> if (below === e.selector) conditional = true
                // the loop condition is evaluated once per iteration too
                is FirLoop -> if (below === e.block || below === e.condition) loop = true
                // a catch block runs only on failure; finally runs on every pass
                is FirTryExpression -> if (below === e.tryBlock || e.catches.any { it === below }) conditional = true
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
                // a named function, accessor or constructor (FirFunction: FirSimpleFunction before Kotlin 2.3.20, FirNamedFunction after)
                is FirFunction -> break
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
 * initialised by `rememberContextOf(...)` without a default, and no `Ctx.Provider(...)` appears in this file
 * (hooks-test's `Ctx provides v` / `provide(Ctx, v)` count as providing it).
 */
object MissingProviderChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    /** Reports E2 for this call. */
    @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class) // a file's own top-level properties
    override fun check(expression: FirFunctionCall) {
        val id = expression.callableId() ?: return
        if (id.packageName != HOOKS || id.callableName.asString() != "rememberContext") return
        val arg = expression.arguments.firstOrNull() as? FirPropertyAccessExpression ?: return
        val prop = arg.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return
        if (prop.isLocalProperty()) return
        val file = context.containingFileSymbol?.fir ?: return
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
                    val name = id?.callableName?.asString()
                    // Ctx.Provider(v) { }, and the hooks-test fakes `Ctx provides v` / `provide(Ctx, v)`
                    val provided = when {
                        id == null -> null
                        id.packageName == HOOKS && name == "Provider" -> element.extensionReceiver
                        id.packageName == HOOKS_TEST && name == "provides" -> element.extensionReceiver
                        id.packageName == HOOKS_TEST && name == "provide" -> element.arguments.firstOrNull()
                        else -> null
                    }
                    val access = provided as? FirQualifiedAccessExpression
                    (access?.calleeReference?.toResolvedCallableSymbol() as? FirPropertySymbol)?.let(out::add)
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
    context(context: CheckerContext, reporter: DiagnosticReporter)
    /** Reports B5 for this call. */
    @OptIn(SymbolInternals::class)
    override fun check(expression: FirFunctionCall) {
        val sym = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val id = sym.callableId ?: return
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
                        if (p != null && p.isLocalProperty()) {
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

private val COMPOSABLE = ClassId(COMPOSE_RUNTIME, Name.identifier("Composable"))

/** Members whose result differs on every evaluation (time, randomness, fresh ids), by owner class. */
private val UNSTABLE_MEMBERS = mapOf(
    "java.lang.System" to setOf("nanoTime", "currentTimeMillis"),
    "java.lang.Math" to setOf("random"),
    "java.lang.StrictMath" to setOf("random"),
    "java.util.UUID" to setOf("randomUUID"),
    "kotlin.uuid.Uuid.Companion" to setOf("random"),
    "java.util.concurrent.ThreadLocalRandom" to setOf("current"),
    "android.os.SystemClock" to setOf("elapsedRealtime", "uptimeMillis", "elapsedRealtimeNanos", "currentThreadTimeMillis"),
    "kotlin.time.TimeSource.Monotonic" to setOf("markNow"),
)

/** Extension functions on collections / ranges / strings that pick a random element. */
private val RANDOM_PACKAGES = setOf("kotlin.collections", "kotlin.ranges", "kotlin.text")
private val RANDOM_NAMES = setOf("random", "randomOrNull", "shuffled")

/** Calls whose result differs on every evaluation (time, randomness, fresh ids). */
private fun CallableId.isUnstable(): Boolean {
    val pkg = packageName.asString()
    val cls = className?.asString()
    val name = callableName.asString()
    val members = UNSTABLE_MEMBERS["$pkg.$cls"]
    return when {
        members != null -> name in members
        pkg == "kotlin.random" -> cls?.startsWith("Random") == true // Random.nextInt(), Random.Default..., not Random(seed)
        pkg in RANDOM_PACKAGES -> name in RANDOM_NAMES
        else -> name == "now" && (pkg.startsWith("java.time") || pkg.startsWith("kotlinx.datetime") || pkg == "kotlin.time")
    }
}

/** Query APIs whose string key is the identity of an entry / atom, with the name of that parameter. */
private val KEYED = mapOf(
    "cache" to "key",
    "cachedValue" to "key",
    "shareState" to "id",
    "rememberShareState" to "id",
)

/**
 * E3: an unstable key: the key / id argument of `cache(...)` / `cachedValue(...)` / `shareState(...)` / `rememberShareState(...)`
 * calls a time / random / UUID function directly, inside a @Composable function or a customRemember block. Every
 * recomposition then names a new entry: the old one is orphaned and the new one fetches again (a refetch loop).
 * Not reported where the expression is evaluated once: top-level / class initialisers, `remember { }` and other
 * lambdas that are not the hook body itself (event callbacks, effects, memo blocks).
 */
object UnstableCacheKeyChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    /** Reports E3 for this call. */
    override fun check(expression: FirFunctionCall) {
        val id = expression.callableId() ?: return
        if (id.packageName != QUERY) return
        val keyParam = KEYED[id.callableName.asString()] ?: return
        val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
        val key = mapping.entries.firstOrNull { it.value.name.asString() == keyParam }?.key ?: return
        if (!inHookBody(context)) return
        val unstable = findUnstable(key) ?: return
        reporter.reportOn(key.source ?: expression.source, HooksErrors.UNSTABLE_CACHE_KEY, unstable, context)
    }

    private fun inHookBody(context: CheckerContext): Boolean {
        val chain = context.containingElements
        var i = chain.size - 1
        while (i >= 0) {
            when (val e = chain[i]) {
                is FirProperty -> if (!e.symbol.isLocalProperty()) return false
                is FirAnonymousFunction -> {
                    val owner = (i - 1 downTo 0).asSequence().map { chain[it] }
                        .firstOrNull { it is FirFunctionCall && it.arguments.any { a -> a.unwrapLambda() === e } } as FirFunctionCall?
                    val ownerId = owner?.callableId() ?: return false
                    // the hook body itself; key() re-runs it with the body; any other lambda (remember { },
                    // effects, callbacks, memo blocks) is evaluated once or outside composition
                    if (ownerId.isCustomRemember()) return true
                    if (!ownerId.isKey()) return false
                }
                is FirFunction -> return e.hasAnnotation(COMPOSABLE, context.session) // named function, @Composable getter
                else -> {}
            }
            i--
        }
        return false
    }

    private fun findUnstable(key: FirExpression): String? {
        var found: String? = null
        key.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found != null) return
                if (element is FirAnonymousFunction) return // a lambda in the key is not evaluated with it
                if (element is FirFunctionCall) {
                    val id = element.callableId()
                    if (id != null && id.isUnstable()) {
                        found = (id.className?.let { "$it." } ?: "") + id.callableName.asString()
                        return
                    }
                }
                element.acceptChildren(this)
            }
        })
        return found
    }
}
