package dev.ide.interp.compose

import dev.ide.interp.InterpretedLambda
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlin.test.Test
import kotlin.test.assertEquals

class LambdaValueClassReturnTest {
    @Test
    fun nonComposableLambdaReturnBoxesValueClassFromGenericSignature() {
        val lambda = object : InterpretedLambda {
            override val paramCount: Int = 1
            override fun invoke(args: List<Any?>): Any? = 7L
        }
        val callee = ResolvedCallable.Library(
            displayName = "consumeOffsetTag",
            ownerFqn = "dev.ide.interp.compose.LambdaValueClassReturnTestKt",
            methodName = "consumeOffsetTag",
            paramTypes = listOf(KotlinType("kotlin.Function1")),
            isStatic = true,
            isConstructor = false,
            isInline = false,
            descriptorPrecise = true,
        )
        val span = SourceSpan(0, 0)
        val call = RNode.Call(
            callee = callee,
            dispatch = DispatchKind.TOP_LEVEL,
            receiver = null,
            args = listOf(RArg(RNode.Const(lambda, null, span))),
            callSiteKey = CallSiteKey(0),
            source = span,
        )

        val result = ComposeDispatcher().dispatch(call, receiver = null, args = listOf(lambda))

        assertEquals("tag:7:x", result)
    }
}

@JvmInline
value class OffsetTag(val packed: Long)

fun consumeOffsetTag(block: (String) -> OffsetTag): String {
    val tag = block("x")
    return "tag:${tag.packed}:x"
}
