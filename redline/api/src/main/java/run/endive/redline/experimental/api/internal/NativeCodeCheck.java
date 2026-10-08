package run.endive.redline.experimental.api.internal;

import run.endive.redline.experimental.api.NativeCode;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;

/** Checks native code was compiled for this host and module before a runner links it. */
public final class NativeCodeCheck {

    private NativeCodeCheck() {}

    public static NativeCode check(NativeCode code, WasmModule module) {
        if (code == null) {
            throw new WasmEngineException(
                    "No precompiled code provided. Precompile the module with the"
                            + " endive-compiler-maven-plugin and a Redline target.");
        }
        String host = RedlineTarget.detectHost().map(RedlineTarget::triple).orElse(null);
        if (!code.triple().equals(host)) {
            throw new WasmEngineException(
                    "Native code compiled for "
                            + code.triple()
                            + " cannot run on "
                            + (host == null ? "this unsupported platform" : host));
        }
        int bodies = module.codeSection().functionBodyCount();
        if (code.functionBodyCount() != bodies) {
            throw new WasmEngineException(
                    "Native code has "
                            + code.functionBodyCount()
                            + " function bodies but the module has "
                            + bodies);
        }
        long imports =
                module.importSection().stream()
                        .filter(i -> i.importType() == ExternalType.FUNCTION)
                        .count();
        if (code.importCount() != imports) {
            throw new WasmEngineException(
                    "Native code has "
                            + code.importCount()
                            + " imported functions but the module has "
                            + imports);
        }
        return code;
    }
}
