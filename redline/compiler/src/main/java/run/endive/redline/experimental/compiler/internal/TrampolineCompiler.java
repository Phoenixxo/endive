package run.endive.redline.experimental.compiler.internal;

import run.endive.redline.experimental.bridge.internal.CraneliftBridge;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/** Compiles the trampolines between the platform ABI and compiled code's Tail convention. */
final class TrampolineCompiler {

    private final CraneliftBridge bridge;

    TrampolineCompiler(CraneliftBridge bridge) {
        this.bridge = bridge;
    }

    /** Platform ABI {@code (funcPtr, memBase, ctxPtr, args...)} to the function at funcPtr. */
    byte[] entry(FunctionType funcType) {
        beginSig(funcType);
        bridge.exports().compileEntryTrampoline();
        return bridge.compiledCode();
    }

    /** Tail convention {@code (memBase, ctxPtr, args...)} to an import's host stub. */
    ImportTrampoline importCall(FunctionType funcType) {
        beginSig(funcType);
        int stubSlotOffset = bridge.exports().compileImportTrampoline();
        return new ImportTrampoline(bridge.compiledCode(), stubSlotOffset);
    }

    static final class ImportTrampoline {
        final byte[] code;
        final int stubSlotOffset;

        ImportTrampoline(byte[] code, int stubSlotOffset) {
            this.code = code;
            this.stubSlotOffset = stubSlotOffset;
        }
    }

    private void beginSig(FunctionType funcType) {
        var exports = bridge.exports();
        exports.beginTrampolineSig();
        exports.trampolineSigAddParam(CraneliftBridge.TYPE_I64); // memBase
        exports.trampolineSigAddParam(CraneliftBridge.TYPE_I64); // ctxPtr
        for (ValType param : funcType.params()) {
            exports.trampolineSigAddParam(CraneliftBridge.valTypeToBridgeType(param));
        }
        if (funcType.returns().size() > 1) {
            // Multi-return: single i64 dummy return (actual values in argsBuffer)
            exports.trampolineSigAddReturn(CraneliftBridge.TYPE_I64);
        } else {
            for (ValType ret : funcType.returns()) {
                exports.trampolineSigAddReturn(CraneliftBridge.valTypeToBridgeType(ret));
            }
        }
    }
}
