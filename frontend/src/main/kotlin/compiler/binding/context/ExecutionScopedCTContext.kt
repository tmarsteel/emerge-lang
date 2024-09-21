package compiler.binding.context

import compiler.InternalCompilerError
import compiler.ast.Statement
import compiler.ast.Statement.Companion.chain
import compiler.binding.BoundExecutable
import compiler.binding.BoundLoop
import compiler.binding.BoundVariable
import compiler.binding.SemanticallyAnalyzable
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.binding.basetype.BoundMixinStatement
import compiler.binding.context.effect.EphemeralStateClass
import compiler.binding.context.effect.SideEffect
import compiler.reportings.Diagnosis
import compiler.reportings.Reporting
import compiler.util.TakeWhileAndNextIterator.Companion.takeWhileAndNext
import compiler.util.takeWhileIsInstance
import java.util.IdentityHashMap

/**
 * A [CTContext] that is also associated with an execution scope. All code that actually _does_
 * stuff lives in an [ExecutionScopedCTContext]; Pure [CTContext] is for declaration-only situations.
 */
interface ExecutionScopedCTContext : CTContext {
    /**
     * Whether this scope marks the boundary of a scope. A scope in this sense is a collection of
     * [BoundExecutable]s at which's end the deferred code will be executed by default.
     */
    val isScopeBoundary: Boolean

    /**
     * Whether this scope marks the root of a function. Deferred statements further up in the
     * tree of [CTContext]s will not be executed when this scope (or any of its child scopes)
     * exit.
     *
     * [isFunctionRoot] implies [isScopeBoundary]
     */
    val isFunctionRoot: Boolean

    /**
     * true for all contexts that introduce a new exception handling boundary (a try block). For now
     * that is only [ExceptionHandlingExecutionScopedCTContext].
     */
    val isExceptionHandler: Boolean

    /**
     * true iff this context or any of its parents has [isExceptionHandler] = `true`
     */
    val hasExceptionHandler: Boolean

    /**
     * @return Whether this context contains the given variable. Only parent contexts up to and including the
     *         given `boundary` will be searched.
     */
    fun containsWithinBoundary(variable: BoundVariable, boundary: CTContext): Boolean

    /**
     * like [getRepetitionBehaviorRelativeTo] given the direct parent.
     */
    val repetitionRelativeToParent: Repetition

    /**
     * How often code in `this` [ExecutionScopedCTContext] may get executed __relative to__ [indirectParent].
     * @param indirectParent must be the direct or an indirect parent of `this`
     */
    fun getRepetitionBehaviorRelativeTo(indirectParent: CTContext): Repetition

    /**
     * @return [LoopExecutionScopedCTContext.loopNode] from the [LoopExecutionScopedCTContext] that is the closest
     * parent to `this` loop, or `null` if there is no enclosing loop context.
     */
    fun getParentLoop(): BoundLoop<*>?

    /**
     * @return code that has been deferred in _this very_ [ExecutionScopedCTContext], in the **reverse** order
     * of how it was added to [MutableExecutionScopedCTContext.addDeferredCode].
     */
    fun getContextLocalDeferredCode(): Sequence<BoundExecutable<*>>

    /**
     * @return all code that has been deferred in this [ExecutionScopedCTContext]s scope, in the
     * **reverse** order of how it was added to [MutableExecutionScopedCTContext.addDeferredCode].
     */
    fun getScopeLocalDeferredCode(): Sequence<BoundExecutable<*>>

    /**
     * If there is a parent context that has [isExceptionHandler] = `true`, returns all deferred code up to and including
     * that context, in the **reverse** order of how it was added to [MutableExecutionScopedCTContext.addDeferredCode].
     * If there is no parent context marked with [isExceptionHandler], returns an empty sequence.
     */
    fun getExceptionHandlingLocalDeferredCode(): Sequence<BoundExecutable<*>>

    /**
     * If there is a parent context that has [isExceptionHandler] = `true`, returns an empty sequence. This range must
     * be covered by using the return value of [getExceptionHandlingLocalDeferredCode].
     * Otherwise, returns all deferred code starting with the parent context of the one that has [isExceptionHandler] = `true`
     * up to the next parent with [isFunctionRoot] = `true`, in the **reverse** order of how it was added
     * to [MutableExecutionScopedCTContext.addDeferredCode].
     */
    fun getDeferredCodeForThrow(): Sequence<BoundExecutable<*>>

    /**
     * @return all code that has been deferred in this scope and all of its parent [ExecutionScopedCTContext]s up until
     * the [isFunctionRoot] context, in the **reverse** order of how it was added to
     * [MutableExecutionScopedCTContext.addDeferredCode].
     */
    fun getFunctionDeferredCode(): Sequence<BoundExecutable<*>>

    /**
     * Intended to be called solely by [BoundMixinStatement] during [SemanticallyAnalyzable.semanticAnalysisPhase2].
     * Registers this mixin in the parent class context.
     * @return the backing field in which to store the reference to the delegate during the objects lifetime.
     * `null` iff the mixin is not legal in this context. In that case, the reason has been added to [diagnosis]
     * by [registerMixin].
     */
    fun registerMixin(mixinStatement: BoundMixinStatement, diagnosis: Diagnosis): BoundBaseTypeMemberVariable?

    /**
     * @see [ExecutionScopedCTContext.repetitionRelativeToParent]
     */
    enum class Repetition(
        /** whether code in this context could execut more than once */
        val mayRepeat: Boolean,
    ) {
        /** for simple, linear code inside a function */
        EXACTLY_ONCE(false),
        /** loop bodies that cannot be proven at compile time to execute at least once */
        ZERO_OR_MORE(true),
        /** loop bodies that **can** be proven at compile time to execute at least once, e.g. do-while loop bodies */
        ONCE_OR_MORE(true),
        ;
    }
}

open class MutableExecutionScopedCTContext protected constructor(
    val parentContext: CTContext,
    final override val isScopeBoundary: Boolean,
    final override val isFunctionRoot: Boolean,
    override val repetitionRelativeToParent: ExecutionScopedCTContext.Repetition,
) : MutableCTContext(parentContext), ExecutionScopedCTContext {
    init {
        if (isFunctionRoot) {
            require(isScopeBoundary) { "Invariant violated" }
        }
    }

    private val hierarchy: Sequence<CTContext> = generateSequence(this as CTContext) {
        when (it) {
            is MutableExecutionScopedCTContext -> it.parentContext
            is SingleBranchJoinExecutionScopedCTContext -> it.beforeBranch
            is MultiBranchJoinExecutionScopedCTContext -> it.beforeBranch
            else -> null
        }
    }

    private val parentScopeContext: ExecutionScopedCTContext? by lazy {
        val parent = hierarchy.drop(1)
            .filterIsInstance<ExecutionScopedCTContext>()
            .firstOrNull { it.isScopeBoundary }

        if (parent == null) {
            check(isScopeBoundary) {
                "This is an edge ${ExecutionScopedCTContext::class.simpleName} (topmost level), must be a scope boundary. But ${this::isScopeBoundary.name} is false."
            }
        }
        parent
    }

    private val parentFunctionContext: ExecutionScopedCTContext? by lazy {
        val parent = hierarchy.drop(1)
            .filterIsInstance<ExecutionScopedCTContext>()
            .firstOrNull { it.isFunctionRoot }

        if (parent == null) {
            check(isFunctionRoot) {
                "This is an edge ${ExecutionScopedCTContext::class.simpleName} (topmost level), must be a function root. But ${this::isFunctionRoot} is false."
            }
        }
        parent
    }

    private val parentExceptionHandlerContext: ExecutionScopedCTContext? by lazy {
        hierarchy.drop(1)
            .filterIsInstance<ExecutionScopedCTContext>()
            .firstOrNull { it.isExceptionHandler }
    }

    override val isExceptionHandler = false
    override val hasExceptionHandler get() = parentExceptionHandlerContext != null

    private var localDeferredCode: ArrayList<Statement>? = null

    /**
     * Adds code to be returned from [getScopeLocalDeferredCode] and [getFunctionDeferredCode]
     */
    open fun addDeferredCode(code: Statement) {
        if (localDeferredCode == null) {
            localDeferredCode = ArrayList()
        }
        localDeferredCode!!.add(code)
    }

    private fun getDeferredCodeUpToIncluding(boundary: CTContext): Sequence<BoundExecutable<*>> {
        return hierarchy
            .takeWhileAndNext { it !== boundary }
            .flatMap {
                (it as? MutableExecutionScopedCTContext)?.localDeferredCode?.asReversed() ?: emptyList()
            }
            .chain(this)
    }

    override fun getContextLocalDeferredCode(): Sequence<BoundExecutable<*>> {
        return (localDeferredCode ?: emptyList()).asReversed().chain(this)
    }

    override fun getScopeLocalDeferredCode(): Sequence<BoundExecutable<*>> {
        if (isScopeBoundary) {
            return getContextLocalDeferredCode()
        }

        return getDeferredCodeUpToIncluding(parentScopeContext ?: this)
    }

    override fun getExceptionHandlingLocalDeferredCode(): Sequence<BoundExecutable<*>> {
        parentExceptionHandlerContext
            ?.let { return getDeferredCodeUpToIncluding(it) }

        return emptySequence()
    }

    override fun getDeferredCodeForThrow(): Sequence<BoundExecutable<*>> {
        val parentEHContext = parentExceptionHandlerContext
        if (parentEHContext != null) {
            return emptySequence()
        }

        return getFunctionDeferredCode()
    }

    override fun getFunctionDeferredCode(): Sequence<BoundExecutable<*>> {
        return getDeferredCodeUpToIncluding(parentFunctionContext ?: this)
    }

    fun addVariable(boundVariable: BoundVariable) {
        if (boundVariable.context !in hierarchy) {
            throw InternalCompilerError("Cannot add a variable that has been bound to a different context")
        }

        _variables[boundVariable.name] = boundVariable
    }

    private val sideEffectsBySubjectAndClass: MutableMap<Any, MutableMap<EphemeralStateClass<*, *, *>, MutableList<SideEffect<*>>>> = IdentityHashMap()
    fun trackSideEffect(effect: SideEffect<*>) {
        val byEffectClass = sideEffectsBySubjectAndClass.computeIfAbsent(effect.subject) { HashMap() }
        val effectList = byEffectClass.computeIfAbsent(effect.stateClass) { ArrayList(2) }
        effectList.add(effect)
    }

    override fun <Subject : Any, State> getEphemeralState(stateClass: EphemeralStateClass<Subject, State, *>, subject: Subject): State {
        val parentState = parentContext.getEphemeralState(stateClass, subject)
        val selfEffects = sideEffectsBySubjectAndClass[subject]?.get(stateClass) ?: return parentState

        // trackSideEffect is responsible for the type safety!
        @Suppress("UNCHECKED_CAST")
        selfEffects as List<SideEffect<Subject>>
        @Suppress("UNCHECKED_CAST")
        stateClass as EphemeralStateClass<Subject, State, in SideEffect<Subject>>

        return selfEffects.fold(parentState, stateClass::fold)
    }

    override fun resolveVariable(name: String, fromOwnFileOnly: Boolean): BoundVariable? {
        _variables[name]?.let { return it }

        val fromImport = if (fromOwnFileOnly) null else {
            imports
                .asSequence()
                .mapNotNull { it.getVariableOfName(name) }
                .firstOrNull()
        }

        return fromImport ?: parentContext.resolveVariable(name, fromOwnFileOnly)
    }

    override fun containsWithinBoundary(variable: BoundVariable, boundary: CTContext): Boolean {
        if (_variables.containsValue(variable)) return true

        if (this === boundary) {
            return false
        }

        return (parentContext as? ExecutionScopedCTContext)?.containsWithinBoundary(variable, boundary) ?: false
    }

    override fun getRepetitionBehaviorRelativeTo(indirectParent: CTContext): ExecutionScopedCTContext.Repetition {
        return hierarchy
            .takeWhileIsInstance<ExecutionScopedCTContext>()
            .takeWhile { it != indirectParent }
            .map { it.repetitionRelativeToParent }
            .fold(ExecutionScopedCTContext.Repetition.EXACTLY_ONCE) { carry, repetition ->
                when (carry) {
                    ExecutionScopedCTContext.Repetition.EXACTLY_ONCE -> repetition
                    ExecutionScopedCTContext.Repetition.ONCE_OR_MORE -> when(repetition) {
                        ExecutionScopedCTContext.Repetition.EXACTLY_ONCE -> carry
                        ExecutionScopedCTContext.Repetition.ONCE_OR_MORE -> ExecutionScopedCTContext.Repetition.ONCE_OR_MORE
                        ExecutionScopedCTContext.Repetition.ZERO_OR_MORE -> ExecutionScopedCTContext.Repetition.ZERO_OR_MORE
                    }
                    ExecutionScopedCTContext.Repetition.ZERO_OR_MORE -> when(repetition) {
                        ExecutionScopedCTContext.Repetition.EXACTLY_ONCE -> carry
                        ExecutionScopedCTContext.Repetition.ONCE_OR_MORE -> ExecutionScopedCTContext.Repetition.ZERO_OR_MORE
                        ExecutionScopedCTContext.Repetition.ZERO_OR_MORE -> ExecutionScopedCTContext.Repetition.ZERO_OR_MORE
                    }
                }
            }
    }

    override fun getParentLoop(): BoundLoop<*>? {
        return hierarchy
            .filterIsInstance<LoopExecutionScopedCTContext<*>>()
            .firstOrNull()
            ?.loopNode
    }

    override fun registerMixin(mixinStatement: BoundMixinStatement, diagnosis: Diagnosis): BoundBaseTypeMemberVariable? {
        if (parentContext !is ExecutionScopedCTContext) {
            diagnosis.add(Reporting.mixinNotAllowed(mixinStatement))
            return null
        }

        return parentContext.registerMixin(mixinStatement, diagnosis)
    }

    override fun toString(): String {
        var str = "MutExecCT["
        if (parentContext is ExecutionScopedCTContext) {
            str += "nested"
        } else {
            str += "root"
        }

        if (isFunctionRoot) {
            str += ", functionRoot"
        } else if (isScopeBoundary) {
            str += ", scopeBoundary"
        }

        str += "]"

        return str
    }

    companion object {
        fun functionRootIn(parentContext: CTContext): MutableExecutionScopedCTContext {
            return MutableExecutionScopedCTContext(parentContext, isScopeBoundary = true, isFunctionRoot = true, repetitionRelativeToParent = ExecutionScopedCTContext.Repetition.EXACTLY_ONCE)
        }

        fun deriveNewScopeFrom(
            parentContext: ExecutionScopedCTContext,
            repetitionRelativeToParent: ExecutionScopedCTContext.Repetition = ExecutionScopedCTContext.Repetition.EXACTLY_ONCE
        ): MutableExecutionScopedCTContext {
            return MutableExecutionScopedCTContext(parentContext, isScopeBoundary = true, isFunctionRoot = false, repetitionRelativeToParent)
        }

        fun <LoopNode : BoundLoop<*>> deriveNewLoopScopeFrom(
            parentContext: ExecutionScopedCTContext,
            isGuaranteedToExecuteAtLeastOnce: Boolean,
            getBoundLoopNode: () -> LoopNode,
        ): LoopExecutionScopedCTContext<LoopNode> {
            return LoopExecutionScopedCTContext(parentContext, isGuaranteedToExecuteAtLeastOnce, getBoundLoopNode)
        }

        fun deriveFrom(parentContext: ExecutionScopedCTContext): MutableExecutionScopedCTContext {
            return MutableExecutionScopedCTContext(parentContext, isScopeBoundary = false, isFunctionRoot = false, repetitionRelativeToParent = ExecutionScopedCTContext.Repetition.EXACTLY_ONCE)
        }
    }
}

/**
 * Models the context after a conditional branch, where the branch may or may not be taken at runtime.
 * Information from [beforeBranch] is definitely available and [SideEffect]s from [atEndOfBranch] are considered
 * using [EphemeralStateClass.combineMaybe].
 */
class SingleBranchJoinExecutionScopedCTContext(
    internal val beforeBranch: ExecutionScopedCTContext,
    private val atEndOfBranch: ExecutionScopedCTContext,
) : ExecutionScopedCTContext by beforeBranch {
    override fun <Subject : Any, State> getEphemeralState(
        stateClass: EphemeralStateClass<Subject, State, *>,
        subject: Subject,
    ): State {
        val stateBeforeBranch = beforeBranch.getEphemeralState(stateClass, subject)
        val stateAfterBranch = atEndOfBranch.getEphemeralState(stateClass, subject)
        return stateClass.combineMaybe(stateBeforeBranch, stateAfterBranch)
    }
}

/**
 * Models the context after a conditional branch where there are many choices, and it is guaranteed that
 * one of them is taken.
 * Information from [beforeBranch] is definitely available and [SideEffect]s from [atEndOfBranches] are considered
 * using [EphemeralStateClass.intersect]
 */
class MultiBranchJoinExecutionScopedCTContext(
    internal val beforeBranch: ExecutionScopedCTContext,
    private val atEndOfBranches: Iterable<ExecutionScopedCTContext>,
) : ExecutionScopedCTContext by beforeBranch {
    override fun <Subject : Any, State> getEphemeralState(
        stateClass: EphemeralStateClass<Subject, State, *>,
        subject: Subject,
    ): State {
        val states = atEndOfBranches.map { it.getEphemeralState(stateClass, subject) }
        return states.reduce(stateClass::intersect)
    }
}

class LoopExecutionScopedCTContext<LoopNode : BoundLoop<*>>(
    parent: CTContext,
    isGuaranteedToExecuteAtLeastOnce: Boolean,
    private val getBoundLoopNode: () -> LoopNode,
) : MutableExecutionScopedCTContext(
    parent,
    true,
    false,
    if (isGuaranteedToExecuteAtLeastOnce) {
        ExecutionScopedCTContext.Repetition.ONCE_OR_MORE
    } else {
        ExecutionScopedCTContext.Repetition.ZERO_OR_MORE
    }
) {
    override val isExceptionHandler = false
    val loopNode: LoopNode by lazy(getBoundLoopNode)
}

class ExceptionHandlingExecutionScopedCTContext(
    parent: CTContext,
) : MutableExecutionScopedCTContext(
    parent,
    isScopeBoundary = true,
    isFunctionRoot = false,
    ExecutionScopedCTContext.Repetition.ZERO_OR_MORE,
) {
    override val isExceptionHandler = true
}