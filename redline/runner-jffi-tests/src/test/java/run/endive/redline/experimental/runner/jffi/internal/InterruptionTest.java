package run.endive.redline.experimental.runner.jffi.internal;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;

public class InterruptionTest {

    @Test
    public void shouldInterruptLoopViaThread() throws InterruptedException {
        try (var instance = buildInstance("compiled/infinite-loop.c.wasm")) {
            var function = instance.export("run");
            assertThreadInterruption(function::apply);
        }
    }

    @Test
    public void shouldInterruptCallViaThread() throws InterruptedException {
        try (var instance = buildInstance("compiled/power.c.wasm")) {
            var function = instance.export("run");
            assertThreadInterruption(() -> function.apply(100));
        }
    }

    @Test
    public void callsDoNotStartAThreadEach() {
        try (var instance = buildInstance("compiled/add.wat.wasm")) {
            var add = instance.export("add");
            add.apply(1, 2);
            var threads = ManagementFactory.getThreadMXBean();
            long before = threads.getTotalStartedThreadCount();
            for (int i = 0; i < 1000; i++) {
                assertEquals(i + 1, (int) add.apply(i, 1)[0]);
            }
            // The JVM may start a thread of its own meanwhile; one per call is the bug.
            long started = threads.getTotalStartedThreadCount() - before;
            assertTrue(started < 100, started + " threads started for 1000 calls");
        }
    }

    private static void assertThreadInterruption(Runnable function) throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread =
                new Thread(
                        () -> {
                            var e = assertThrows(WasmEngineException.class, function::run);
                            assertEquals("interrupted", e.getMessage());
                            interrupted.set(true);
                        });
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(100);

        thread.interrupt();
        SECONDS.timedJoin(thread, 10);
        assertTrue(interrupted.get());
    }

    private static Instance buildInstance(String resource) {
        var module = Parser.parse(CorpusResources.getResource(resource));
        return JffiNativeMachineFactory.builder(module)
                .withCompilerFunction(
                        m ->
                                NativeCompiler.compileAll(
                                        RedlineTarget.detectHost().orElseThrow().triple(), m))
                .build();
    }
}
