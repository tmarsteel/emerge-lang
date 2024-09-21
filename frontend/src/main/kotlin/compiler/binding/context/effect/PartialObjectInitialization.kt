package compiler.binding.context.effect

import compiler.binding.BoundVariable
import compiler.binding.SemanticallyAnalyzable
import compiler.binding.basetype.BoundBaseType
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.binding.basetype.BoundMixinStatement
import compiler.binding.context.effect.PartialObjectInitialization.State

/**
 * The analysis happens during [SemanticallyAnalyzable.semanticAnalysisPhase2]; **the [State] is not meaningful
 * before [SemanticallyAnalyzable.semanticAnalysisPhase3]!**
 */
object PartialObjectInitialization : EphemeralStateClass<BoundVariable, PartialObjectInitialization.State, PartialObjectInitialization.Effect> {
    override fun getInitialState(subject: BoundVariable) = State.INITIAL
    override fun fold(state: State, effect: Effect) = state.fold(effect)
    override fun combineMaybe(state: State, advancedMaybe: State) = state.combineMaybe(advancedMaybe)
    override fun intersect(stateOne: State, stateTwo: State) = stateOne.intersect(stateTwo)

    class State(
        private val knownMemberStates: Map<String, VariableInitialization.State>,
        private val initializedMixins: Set<BoundMixinStatement>,
    ) {
        fun fold(effect: Effect): State = when (effect) {
            is Effect.MarkObjectAsEntirelyUninitializedEffect -> {
                check(knownMemberStates.isEmpty()) {
                    "Marking object as completely uninitialized after state of some member has already been tracked - somethings off!"
                }
                State(
                    effect.classDef.memberVariables.associate { it.name to VariableInitialization.State.NOT_INITIALIZED },
                    initializedMixins,
                )
            }
            is Effect.WriteToMemberVariableEffect -> State(
                knownMemberStates + mapOf(
                    effect.member.name to VariableInitialization.State.INITIALIZED
                ),
                initializedMixins,
            )
            is Effect.MixinInitialized -> State(
                knownMemberStates,
                initializedMixins + effect.mixin,
            )
        }

        private fun combineEach(
            other: State,
            fieldStateCombinator: (VariableInitialization.State, VariableInitialization.State) -> VariableInitialization.State,
            mixinStateCombinator: (Set<BoundMixinStatement>, Set<BoundMixinStatement>) -> Set<BoundMixinStatement>,
        ): State {
            val newFieldData = HashMap<String, VariableInitialization.State>(knownMemberStates)
            other.knownMemberStates.forEach { (memberName, otherMemberState) ->
                newFieldData.compute(memberName) { _, selfState ->
                    fieldStateCombinator(selfState ?: VariableInitialization.State.INITIALIZED, otherMemberState)
                }
            }
            val newMixinData = mixinStateCombinator(this.initializedMixins, other.initializedMixins)
            return State(newFieldData, newMixinData)
        }

        fun combineMaybe(advancedMaybe: State) = combineEach(advancedMaybe, VariableInitialization::combineMaybe, { before, _ -> before })
        fun intersect(other: State) = combineEach(other, VariableInitialization::intersect, Set<BoundMixinStatement>::intersect)

        fun getMemberInitializationState(member: BoundBaseTypeMemberVariable): VariableInitialization.State {
            return knownMemberStates[member.name] ?: VariableInitialization.State.INITIALIZED
        }

        fun getUninitializedMembers(classDef: BoundBaseType): Collection<BoundBaseTypeMemberVariable> {
            if (knownMemberStates.isEmpty()) {
                return emptySet()
            }

            return classDef.memberVariables.filter { getMemberInitializationState(it) != VariableInitialization.State.INITIALIZED }
        }

        fun getUninitializedMixins(classDef: BoundBaseType): Set<BoundMixinStatement> {
            return classDef.mixins.toMutableSet().apply {
                removeAll(initializedMixins)
            }
        }

        companion object {
            val INITIAL = State(emptyMap(), emptySet())
        }
    }

    sealed interface Effect : SideEffect<BoundVariable> {
        override val stateClass get() = PartialObjectInitialization

        class MarkObjectAsEntirelyUninitializedEffect(
            override val subject: BoundVariable,
            val classDef: BoundBaseType,
        ) : Effect

        class WriteToMemberVariableEffect(
            override val subject: BoundVariable,
            val member: BoundBaseTypeMemberVariable,
        ) : Effect

        class MixinInitialized(
            override val subject: BoundVariable,
            val mixin: BoundMixinStatement,
        ) : Effect
    }
}