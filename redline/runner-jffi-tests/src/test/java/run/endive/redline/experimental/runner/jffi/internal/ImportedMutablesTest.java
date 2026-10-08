package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.ImportGlobal;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportTable;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * An import belongs to the caller, so what the module writes to it has to be
 * visible through the caller's own object. Copying the value in instead leaves the
 * two to drift, which makes the same jar answer differently depending on whether
 * it got native code on this platform.
 */
public class ImportedMutablesTest {

    private static final int WRITTEN = 23130;

    @Test
    public void theCallerSeesWhatTheModuleWroteToItsGlobal() {
        var counter = nativeGlobal();
        try (var instance = build(nativeMemory(), nativeTable(), counter)) {
            instance.export("run").apply();
            assertEquals(
                    11L,
                    counter.getValue(),
                    "the caller's own global must carry what the module wrote");
        }
    }

    @Test
    public void theCallerSeesWhatTheModuleWroteToItsMemory() {
        var memory = nativeMemory();
        try (var instance = build(memory, nativeTable(), nativeGlobal())) {
            instance.export("run").apply();
            assertEquals(
                    WRITTEN, memory.readInt(0), "the caller's own memory must carry the write");
        }
    }

    @Test
    public void theModuleReadsWhatTheCallerWroteToItsMemory() {
        var memory = nativeMemory();
        try (var instance = build(memory, nativeTable(), nativeGlobal())) {
            memory.writeI32(16, 21);
            instance.export("doubleAt").apply(16);
            assertEquals(
                    42,
                    memory.readInt(20),
                    "the module must read back what the caller put in its memory");
        }
    }

    @Test
    public void theCallerSeesWhatTheModuleWroteToItsTable() {
        var table = nativeTable();
        try (var instance = build(nativeMemory(), table, nativeGlobal())) {
            instance.export("run").apply();
            assertNotEquals(
                    Value.REF_NULL_VALUE,
                    table.ref(0),
                    "the caller's own table must carry the funcref the module stored");
        }
    }

    @Test
    public void matchesTheInterpreter() {
        var counter =
                GlobalInstance.builder()
                        .value(Value.i32(10))
                        .mutabilityType(MutabilityType.Var)
                        .build();
        var reference =
                run.endive.runtime.Instance.builder(module())
                        .withImportValues(
                                importsFor(
                                        new run.endive.runtime.ByteBufferMemory(
                                                new MemoryLimits(1, 1)),
                                        new TableInstance(table(), Value.REF_NULL_VALUE),
                                        counter))
                        .build();
        reference.export("run").apply();

        var native_ = nativeGlobal();
        try (var instance = build(nativeMemory(), nativeTable(), native_)) {
            instance.export("run").apply();
            assertEquals(
                    counter.getValue(),
                    native_.getValue(),
                    "redline must leave the caller's global where the interpreter does");
        }
    }

    private static Memory nativeMemory() {
        return JffiNativeMachineFactory.createMemory(new MemoryLimits(1, 1));
    }

    private static TableInstance nativeTable() {
        return JffiNativeMachineFactory.createImportTable(table(), Value.REF_NULL_VALUE);
    }

    private static GlobalInstance nativeGlobal() {
        return JffiNativeMachineFactory.createImportGlobal(10L, ValType.I32, MutabilityType.Var);
    }

    private static Table table() {
        return new Table(ValType.FuncRef, new TableLimits(2, 2));
    }

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/imported-mutables.wat.wasm"));
    }

    private static ImportValues importsFor(
            Memory memory, TableInstance table, GlobalInstance counter) {
        return ImportValues.builder()
                .addMemory(new ImportMemory("env", "memory", memory))
                .addTable(new ImportTable("env", "table", table))
                .addGlobal(new ImportGlobal("env", "counter", counter))
                .build();
    }

    private static run.endive.runtime.Instance build(
            Memory memory, TableInstance table, GlobalInstance counter) {
        var module = module();
        return JffiNativeMachineFactory.builder(module)
                .withImportValues(importsFor(memory, table, counter))
                .withPrecompiledCode(
                        NativeCompiler.compile(
                                RedlineTarget.detectHost().orElseThrow().triple(), module))
                .build();
    }
}
