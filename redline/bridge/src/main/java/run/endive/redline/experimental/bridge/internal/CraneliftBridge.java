package run.endive.redline.experimental.bridge.internal;

import java.nio.charset.StandardCharsets;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.types.ValType;

public final class CraneliftBridge implements AutoCloseable {

    private final Instance instance;
    private final WasiPreview1 wasi;
    private final CraneliftBridge_ModuleExports exports;

    public CraneliftBridge() {
        var wasiOpts = WasiOptions.builder().build();
        wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();

        instance =
                Instance.builder(Cranelift.load())
                        .withImportValues(imports)
                        .withMachineFactory(Cranelift::create)
                        .build();
        exports = new CraneliftBridge_ModuleExports(instance);
    }

    @Override
    public void close() {
        instance.close();
        wasi.close();
    }

    public static final int TYPE_I32 = 0;
    public static final int TYPE_I64 = 1;
    public static final int TYPE_F32 = 2;
    public static final int TYPE_F64 = 3;

    public static int valTypeToBridgeType(ValType type) {
        if (type.equals(ValType.I32)) {
            return TYPE_I32;
        }
        if (type.equals(ValType.I64)) {
            return TYPE_I64;
        }
        if (type.equals(ValType.F32)) {
            return TYPE_F32;
        }
        if (type.equals(ValType.F64)) {
            return TYPE_F64;
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return TYPE_I64;
        }
        throw new UnsupportedOperationException("Unsupported ValType for native: " + type);
    }

    public void init(String target) {
        byte[] bytes = target.getBytes(StandardCharsets.UTF_8);
        int ptr = exports.wasmMalloc(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            exports.memory().writeByte(ptr + i, bytes[i]);
        }
        exports.init(ptr, bytes.length);
        exports.wasmFree(ptr, bytes.length);
    }

    public CraneliftBridge_ModuleExports exports() {
        return exports;
    }

    public byte[] compile() {
        exports.compile();
        return compiledCode();
    }

    /** The code produced by the last function or trampoline compilation. */
    public byte[] compiledCode() {
        return exports.memory().readBytes(exports.getCodePtr(), exports.getCodeLen());
    }
}
