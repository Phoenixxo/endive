package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/**
 * A function placed in a table runs as part of the instance that defined it,
 * whichever instance calls it through the table.
 * The spec suite does not notice when it does not: its shared-table functions return constants,
 * so they behave the same in any instance.
 * The functions here read their own global and memory, call their own import, and trap.
 *
 * <p>This is how wit-component lays out every component:
 * a shim module's functions are call_indirects through a table that a fixup module fills with
 * another instance's functions.
 */
public class CrossInstanceCallIndirectTest {

    /**
     * Owns the functions.
     * Its global and memory hold 42 and 1000, which the caller's do not.
     */
    private static final String OWNER =
            """
            (module
              (import "host" "seven" (func $host (result i32)))
              (memory 1)
              (global $g (mut i32) (i32.const 42))
              (table (export "tab") 6 funcref)
              (data (i32.const 16) "\\e8\\03\\00\\00")
              (func $readGlobal (result i32) global.get $g)
              (func $readMemory (result i32) (i32.load (i32.const 16)))
              (func $trap (result i32) unreachable)
              (func $pair (result i32 i32) global.get $g (i32.load (i32.const 16)))
              (func $bumpGlobal (result i32)
                (global.set $g (i32.add (global.get $g) (i32.const 1)))
                global.get $g)
              (elem (i32.const 0) $readGlobal $readMemory $host $trap $pair $bumpGlobal))
            """;

    /**
     * Calls through the owner's table.
     * Its own global and memory differ from the owner's,
     * and its types are declared in a different order, so type indices differ between the two.
     */
    private static final String CALLER =
            """
            (module
              (type $byIndex (func (param i32) (result i32)))
              (type $i64 (func (result i64)))
              (type $pair (func (result i32 i32)))
              (type $i32 (func (result i32)))
              (import "owner" "tab" (table 6 funcref))
              (memory 1)
              (global $g (mut i32) (i32.const -1))
              (func (export "call") (type $byIndex)
                (call_indirect (type $i32) (local.get 0)))
              (func (export "tailCall") (type $byIndex)
                (return_call_indirect (type $i32) (local.get 0)))
              (func (export "callPair") (result i32)
                (call_indirect (type $pair) (i32.const 4))
                i32.sub)
              (func (export "wrongType") (result i64)
                (call_indirect (type $i64) (i32.const 0))))
            """;

    @Test
    public void calleeReadsItsOwnGlobal() {
        try (var linked = linkNative()) {
            assertEquals(
                    42, call(linked.caller, "call", 0), "the owner's global, not the caller's");
        }
    }

    @Test
    public void calleeReadsItsOwnMemory() {
        try (var linked = linkNative()) {
            assertEquals(
                    1000, call(linked.caller, "call", 1), "the owner's memory, not the caller's");
        }
    }

    @Test
    public void calleeWritesItsOwnGlobal() {
        try (var linked = linkNative()) {
            assertEquals(43, call(linked.caller, "call", 5));
            assertEquals(44, call(linked.caller, "call", 5));
            assertEquals(
                    44, (int) linked.owner.global(0).getValue(), "the write landed in the owner");
        }
    }

    @Test
    public void calleeCallsItsOwnImport() {
        try (var linked = linkNative()) {
            assertEquals(7, call(linked.caller, "call", 2));
        }
    }

    @Test
    public void tailCallRunsInTheOwner() {
        try (var linked = linkNative()) {
            assertEquals(42, call(linked.caller, "tailCall", 0));
            assertEquals(1000, call(linked.caller, "tailCall", 1));
        }
    }

    @Test
    public void multiValueResultsReachTheCaller() {
        try (var linked = linkNative()) {
            assertEquals(42 - 1000, (int) linked.caller.export("callPair").apply()[0]);
        }
    }

    @Test
    public void calleeTrapStopsTheCaller() {
        try (var linked = linkNative()) {
            var e = assertThrows(WasmEngineException.class, () -> call(linked.caller, "call", 3));
            assertTrue(e.getMessage().contains("unreachable"), e.getMessage());
        }
    }

    @Test
    public void signatureIsCheckedByStructure() {
        try (var linked = linkNative()) {
            var e =
                    assertThrows(
                            WasmEngineException.class,
                            () -> linked.caller.export("wrongType").apply());
            assertTrue(e.getMessage().contains("indirect call type mismatch"), e.getMessage());
        }
    }

    @Test
    public void tableReportsTheOwnerOfEachEntry() {
        try (var linked = linkNative()) {
            assertSame(linked.owner, linked.owner.table(0).instance(0));
        }
    }

    /**
     * The table belongs to a native instance and an interpreted one fills it with its own
     * functions, as a fixup module on another engine would.
     */
    @Test
    public void entriesFromAnInterpretedInstance() {
        var store = new Store();
        try (var tableOwner =
                nativeBuilder(
                                parse(
                                        """
                                        (module
                                          (type $i32 (func (result i32)))
                                          (table (export "tab") 2 funcref)
                                          (global $g i32 (i32.const -1))
                                          (func $own (result i32) global.get $g)
                                          (elem (i32.const 1) $own)
                                          (func (export "call") (param i32) (result i32)
                                            (call_indirect (type $i32) (local.get 0))))
                                        """))
                        .build()) {
            store.register("owner", tableOwner);
            try (var filler =
                    Instance.builder(
                                    parse(
                                            """
                                            (module
                                              (import "owner" "tab" (table 2 funcref))
                                              (global $g i32 (i32.const 42))
                                              (func $answer (result i32) global.get $g)
                                              (elem (i32.const 0) $answer))
                                            """))
                            .withImportValues(store.toImportValues())
                            .build()) {
                assertEquals(42, call(tableOwner, "call", 0), "the interpreted filler's function");
                assertEquals(-1, call(tableOwner, "call", 1), "the table owner's own function");
                assertSame(filler, tableOwner.table(0).instance(0));
            }
        }
    }

    private record Linked(Instance owner, Instance caller) implements AutoCloseable {
        @Override
        public void close() {
            caller.close();
            owner.close();
        }
    }

    private static Linked linkNative() {
        var seven =
                new HostFunction(
                        "host",
                        "seven",
                        FunctionType.of(List.of(), List.of(ValType.I32)),
                        (inst, args) -> new long[] {7});
        var store = new Store().addFunction(seven);
        var owner = nativeBuilder(parse(OWNER)).withImportValues(store.toImportValues()).build();
        store.register("owner", owner);
        var caller = nativeBuilder(parse(CALLER)).withImportValues(store.toImportValues()).build();
        return new Linked(owner, caller);
    }

    private static int call(Instance instance, String export, int index) {
        return (int) instance.export(export).apply(index)[0];
    }

    private static NativeMachineFactory.Builder nativeBuilder(WasmModule module) {
        return NativeMachineFactory.builder(module)
                .withCompilerFunction(
                        m ->
                                NativeCompiler.compileAll(
                                        RedlineTarget.detectHost().orElseThrow().triple(), m));
    }

    private static WasmModule parse(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }
}
