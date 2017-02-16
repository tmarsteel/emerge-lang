import compiler.lexer.Keyword
import compiler.lexer.Operator.*
import compiler.parser.grammar.postprocess
import compiler.parser.grammar.rule
import compiler.parser.postproc.ImportPostprocessor

val Import = rule {
    keyword(Keyword.IMPORT)

    __definitive()

    atLeast(1) {
        identifier()
        operator(DOT)
    }
    identifier(acceptedOperators = listOf(ASTERISK))
    operator(NEWLINE)
}
.postprocess(::ImportPostprocessor)

val Type = rule {
    identifier()
    optional {
        operator(QUESTION_MARK)
    }
}

val CommaSeparatedTypedParameters = rule {
    identifier()
    operator(COLON)
    __definitive()
    ref(Type)
    
    atLeast(0) {
        operator(COMMA)
        identifier()
        operator(COLON)
        __definitive()
        ref(Type)
    }
}

val TypedParameterList = rule {
    operator(PARANT_OPEN)
    
    optional {
        ref(CommaSeparatedTypedParameters)
    }
    
    operator(PARANT_CLOSE)
    __definitive()
}

val FunctionDeclaration = rule {
    keyword(Keyword.FUNCTION)

    __definitive()

    identifier()
    ref(TypedParameterList)
    optional {
        operator(RETURNS)
        identifier()
    }

    operator(CBRACE_OPEN)
    operator(CBRACE_CLOSE)
}