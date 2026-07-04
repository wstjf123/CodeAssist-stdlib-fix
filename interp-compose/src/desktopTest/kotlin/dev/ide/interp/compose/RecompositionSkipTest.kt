package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the `$changed` recomposition fast path ([ComposeRuntime.invokeComposable]): when an interpreted
 * source `@Composable` is reached again because its PARENT recomposed, and the arguments it was passed are
 * unchanged, its body must be skipped (`composer.skipToGroupEnd()`) rather than re-interpreted.
 *
 * Structure (mirrors the on-device `ComposeRecompositionSpikeTest`): a NON-restartable root is invoked
 * directly through [ComposeRuntime.invokeComposable] (so it always re-runs — `restartable = false`); the root
 * reads a `MutableState` (subscribing its scope) and calls the interpreted source `Child("x")`. `Child` IS
 * restartable, so it is the one under test. A state write recomposes the root (proven: `rootRuns` reaches 2)
 * while `Child`, whose arg never changes, must stay skipped (`ItemCapture.items` keeps a single entry).
 *
 * Headless harness: the recomposer runs on a dedicated single thread (where composition + recomposition stay
 * confined); the test thread sends frames and polls, all under [withTimeout] so a fault fails fast.
 */
class RecompositionSkipTest {

    @BeforeTest fun reset() {
        ItemCapture.items.clear()
        CounterHolder.state.value = 0
        CounterHolder.rootRuns = 0
        TextFieldCapture.values.clear()
        TextFieldCapture.onValueChange = null
        TextFieldCapture.stateCreations = 0
    }

    private val span = SourceSpan(0, 0)
    private val itemFacade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"
    private val rootKey = 0x5ACE_0002.toInt()

    @Test
    fun childWithUnchangedArgsSkipsWhenParentRecomposes() {
        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val interpreter = Interpreter(functions = childProgram(), dispatcher = dispatcher, composableInvoker = runtime)
        val rootFn = rootReadingStateThenCallingChild()

        runRecompositionTest(content = {
            dispatcher.composer = currentComposer
            try {
                // restartable=false → the root always re-runs on recompose (it is NOT the thing under test).
                runtime.invokeComposable(rootKey, restartable = false, args = emptyList()) {
                    CounterHolder.rootRuns++
                    interpreter.call(rootFn, listOf(CounterHolder.state))
                }
            } finally {
                dispatcher.composer = null
            }
        }) {
            // The root recomposed (rootRuns == 2, asserted in the harness). The restartable Child was reached
            // again with an unchanged arg → its body must have been skipped, so no second item was recorded.
            assertEquals(listOf("x"), ItemCapture.items, "Child has an unchanged arg → its body is skipped on the root's recompose")
        }
    }

    @Test
    fun controlHarnessDrivesRecomposition() = runRecompositionTest(content = { ControlReader() }) {
        // The shared harness assertion (rootRuns reached 2) is the whole point here: it proves a plain
        // composable that read the mutated state recomposes — isolating harness faults from the interpreter.
    }

    @Test
    fun rememberedMutableStateSurvivesTextFieldCallbackRecomposition() {
        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val interpreter = Interpreter(functions = emptyMap(), dispatcher = dispatcher, composableInvoker = runtime)
        val entry = rememberedTextFieldProgram()

        runInteractiveRecompositionTest(
            content = {
                dispatcher.composer = currentComposer
                try {
                    runtime.invokeComposable(rootKey + 1, restartable = false, args = emptyList()) {
                        interpreter.call(entry, emptyList())
                    }
                } finally {
                    dispatcher.composer = null
                }
            },
            interact = {
                TextFieldCapture.onValueChange?.invoke("a") ?: error("text field callback was not captured")
            },
            settled = { TextFieldCapture.values.lastOrNull() == "a" },
        ) {
            assertEquals(listOf("", "a"), TextFieldCapture.values, "the typed value should survive recomposition")
            assertEquals(1, TextFieldCapture.stateCreations, "`remember { mutableStateOf(\"\") }` should create one delegate")
        }
    }

    @Test
    fun rememberedMutableStateFeedsUpdatedTextBackBeforeNextInput() {
        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val interpreter = Interpreter(functions = emptyMap(), dispatcher = dispatcher, composableInvoker = runtime)
        val entry = rememberedTextFieldProgram()

        runInteractiveRecompositionTest(
            content = {
                dispatcher.composer = currentComposer
                try {
                    runtime.invokeComposable(rootKey + 2, restartable = false, args = emptyList()) {
                        interpreter.call(entry, emptyList())
                    }
                } finally {
                    dispatcher.composer = null
                }
            },
            interact = {
                TextFieldCapture.onValueChange?.invoke("a") ?: error("text field callback was not captured")
            },
            settled = { TextFieldCapture.values.lastOrNull() == "a" },
            afterSettled = {
                TextFieldCapture.onValueChange?.invoke("ab") ?: error("text field callback was not captured after first input")
            },
            settledAgain = { TextFieldCapture.values.lastOrNull() == "ab" },
        ) {
            assertEquals(listOf("", "a", "ab"), TextFieldCapture.values, "the second edit should see the first edit's state")
            assertEquals(1, TextFieldCapture.stateCreations, "`remember` should not recreate the state between edits")
        }
    }

    @Test
    fun stateInsideRestartableChildFeedsUpdatedTextBackBeforeNextInput() {
        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val child = rememberedTextFieldProgram("StatefulTextField")
        val root = rootCallingStatefulChild()
        val interpreter = Interpreter(functions = mapOf("StatefulTextField/0" to child), dispatcher = dispatcher, composableInvoker = runtime)

        runInteractiveRecompositionTest(
            content = {
                dispatcher.composer = currentComposer
                try {
                    runtime.invokeComposable(rootKey + 3, restartable = false, args = emptyList()) {
                        interpreter.call(root, emptyList())
                    }
                } finally {
                    dispatcher.composer = null
                }
            },
            interact = {
                TextFieldCapture.onValueChange?.invoke("a") ?: error("text field callback was not captured")
            },
            settled = { TextFieldCapture.values.lastOrNull() == "a" },
            afterSettled = {
                TextFieldCapture.onValueChange?.invoke("ab") ?: error("text field callback was not captured after first input")
            },
            settledAgain = { TextFieldCapture.values.lastOrNull() == "ab" },
        ) {
            assertEquals(listOf("", "a", "ab"), TextFieldCapture.values, "state inside a child composable must update its text field")
            assertEquals(1, TextFieldCapture.stateCreations, "the child composable's `remember` should keep one state delegate")
        }
    }

    /** `fun Root(s: MutableState) { s.value /* subscribe */; Child("x") }`. The state arrives as a param and is
     *  read with `ownerFqn = null` (an instance getter on the receiver), matching the proven device spike. */
    private fun rootReadingStateThenCallingChild(): ResolvedFunction {
        val stateSlot = SlotId(0)
        val readState = RNode.PropertyGet(
            receiver = RNode.Name(Binding.Param(stateSlot, "s"), span),
            binding = Binding.Property("value", ownerFqn = null, backingField = false), source = span,
        )
        val childCall = RNode.Call(
            ResolvedCallable.Source("Child", "Child/1", listOf("label"), isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const("x", null, span))),
            callSiteKey = CallSiteKey(2), source = span,
        )
        return ResolvedFunction("Root", listOf(RParam(stateSlot, "s", null)), RNode.Block(listOf(readState, childCall), false, span), emptyList(), returnsUnit = true)
    }

    /** `@Composable fun Child(label: String) { FakeItem(label) }` — each body run records one item. */
    private fun childProgram(): Map<String, ResolvedFunction> {
        val labelSlot = SlotId(0)
        val childBody = RNode.Block(
            listOf(
                RNode.Call(
                    ResolvedCallable.Library("FakeItem", itemFacade, "FakeItem", emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
                    DispatchKind.TOP_LEVEL, receiver = null,
                    args = listOf(RArg(RNode.Name(Binding.Param(labelSlot, "label"), span))),
                    callSiteKey = CallSiteKey(100), source = span,
                ),
            ),
            isExpression = false, source = span,
        )
        val childFn = ResolvedFunction("Child", listOf(RParam(labelSlot, "label", null)), childBody, emptyList(), returnsUnit = true)
        return mapOf("Child/1" to childFn)
    }

    /** Lowered shape of:
     *
     * ```
     * @Composable fun Editable() {
     *   var text by remember { mutableStateOf("") }
     *   StateTextField(value = text, onValueChange = { text = it })
     * }
     * ```
     */
    private fun rememberedTextFieldProgram(name: String = "Editable"): ResolvedFunction {
        val delegateSlot = SlotId(0)
        val inputSlot = SlotId(1)
        val valueProperty = Binding.Property("value", "androidx.compose.runtime.MutableState", backingField = false)
        fun delegateRef() = RNode.Name(Binding.Local(delegateSlot, "text", mutable = true), span)

        val stateFactory = RNode.Call(
            ResolvedCallable.Library(
                displayName = "newTextState", ownerFqn = "dev.ide.interp.compose.RecompositionSkipTestKt",
                methodName = "newTextState", paramTypes = listOf(dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String")),
                isStatic = true, isConstructor = false, isInline = false,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const("", null, span))),
            callSiteKey = CallSiteKey(201), source = span,
        )
        val remember = RNode.Call(
            ResolvedCallable.Library(
                displayName = "remember", ownerFqn = "androidx.compose.runtime.ComposablesKt",
                methodName = "remember", paramTypes = listOf(dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Function0")),
                isStatic = true, isConstructor = false, isInline = true, isComposable = true,
                paramNames = listOf("calculation"),
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Lambda(emptyList(), stateFactory, emptyList(), span), trailingLambda = true)),
            callSiteKey = CallSiteKey(202), source = span,
        )
        val localTextDelegate = RNode.LocalVar(delegateSlot, "text", mutable = true, initializer = remember, source = span)
        val textRead = RNode.PropertyGet(delegateRef(), valueProperty, span)
        val onValueChange = RNode.Lambda(
            params = listOf(RParam(inputSlot, "it", dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"))),
            body = RNode.PropertySet(delegateRef(), valueProperty, RNode.Name(Binding.Param(inputSlot, "it"), span), span),
            captures = listOf(Binding.Local(delegateSlot, "text", mutable = true)),
            source = span,
        )
        val field = RNode.Call(
            ResolvedCallable.Library(
                displayName = "StateTextField", ownerFqn = "dev.ide.interp.compose.RecompositionSkipTestKt",
                methodName = "StateTextField",
                paramTypes = listOf(
                    dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
                    dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Function1"),
                ),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                paramNames = listOf("value", "onValueChange"),
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(textRead, name = "value"), RArg(onValueChange, name = "onValueChange")),
            callSiteKey = CallSiteKey(203), source = span,
        )
        return ResolvedFunction(
            name, emptyList(), RNode.Block(listOf(localTextDelegate, field), isExpression = false, source = span),
            emptyList(), returnsUnit = true,
        )
    }

    private fun rootCallingStatefulChild(): ResolvedFunction {
        val childCall = RNode.Call(
            ResolvedCallable.Source("StatefulTextField", "StatefulTextField/0", emptyList(), isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(),
            callSiteKey = CallSiteKey(301), source = span,
        )
        return ResolvedFunction("PreviewRoot", emptyList(), RNode.Block(listOf(childCall), isExpression = false, source = span), emptyList(), returnsUnit = true)
    }

    // --- headless recomposition harness ---

    /**
     * Composes [content], asserts it ran once (`rootRuns == 1`), mutates the shared state, drives frames until
     * the parent recomposes (`rootRuns == 2`), then runs [verify]. Composition + recomposition are confined to
     * one dedicated thread; the test thread only sends frames. The whole thing is time-boxed.
     */
    private fun runRecompositionTest(content: @Composable () -> Unit, verify: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "recompose-test") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle } // loop up + apply observer registered

                    val composition = withContext(dispatcher) {
                        Composition(UnitApplier, recomposer).also { c -> c.setContent { content() } }
                    }
                    withContext(dispatcher) { assertEquals(1, CounterHolder.rootRuns, "composes once initially") }

                    // Write the state the root read → invalidates that scope only; pump frames until it recomposes.
                    withContext(dispatcher) {
                        CounterHolder.state.value = 1
                        Snapshot.sendApplyNotifications()
                    }
                    var frame = 0L
                    while (CounterHolder.rootRuns < 2) {
                        clock.sendFrame(frame++)
                        delay(5)
                    }

                    withContext(dispatcher) {
                        assertEquals(2, CounterHolder.rootRuns, "the parent must actually recompose")
                        verify()
                        composition.dispose()
                    }
                    recomposer.cancel()
                    runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runInteractiveRecompositionTest(
        content: @Composable () -> Unit,
        interact: () -> Unit,
        settled: () -> Boolean,
        afterSettled: (() -> Unit)? = null,
        settledAgain: (() -> Boolean)? = null,
        verify: () -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "interactive-recompose-test") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }

                    val composition = withContext(dispatcher) {
                        Composition(UnitApplier, recomposer).also { c -> c.setContent { content() } }
                    }
                    withContext(dispatcher) {
                        assertEquals(listOf(""), TextFieldCapture.values, "initial composition should read the empty state")
                        interact()
                        Snapshot.sendApplyNotifications()
                    }
                    var frame = 0L
                    while (!settled()) {
                        clock.sendFrame(frame++)
                        delay(5)
                    }
                    if (afterSettled != null && settledAgain != null) {
                        withContext(dispatcher) {
                            afterSettled()
                            Snapshot.sendApplyNotifications()
                        }
                        while (!settledAgain()) {
                            clock.sendFrame(frame++)
                            delay(5)
                        }
                    }

                    withContext(dispatcher) {
                        verify()
                        composition.dispose()
                    }
                    recomposer.cancel()
                    runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}

/** A `MutableState` shared with the interpreted root (passed in as its `s` param) plus a counter of how many
 *  times the root body ran. `@Volatile` because the test thread polls it while the dedicated recompose thread
 *  writes it. */
object CounterHolder {
    val state: MutableState<Int> = mutableStateOf(0)
    @Volatile var rootRuns = 0
}

/** Control composable for the harness sanity check: reads the same state directly and bumps the run counter. */
@Composable
fun ControlReader() {
    CounterHolder.rootRuns++
    @Suppress("UNUSED_EXPRESSION") CounterHolder.state.value
}

fun newTextState(value: String): MutableState<String> {
    TextFieldCapture.stateCreations++
    return mutableStateOf(value)
}

@Composable
fun StateTextField(value: String, onValueChange: (String) -> Unit) {
    TextFieldCapture.values.add(value)
    TextFieldCapture.onValueChange = onValueChange
}

object TextFieldCapture {
    val values = ArrayList<String>()
    var onValueChange: ((String) -> Unit)? = null
    var stateCreations = 0
}
