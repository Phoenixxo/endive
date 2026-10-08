package run.endive.redline.experimental.runner.jffi;

import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.NativeMachineFactoryProvider;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.Value;

public final class JffiMachineFactoryProvider implements NativeMachineFactoryProvider {

    @Override
    public Instance.Builder builder(WasmModule module, NativeCode precompiledCode) {
        return JffiNativeMachineFactory.builder(module)
                .withPrecompiledCode(precompiledCode)
                .toInstanceBuilder();
    }

    @Override
    public Memory createMemory(MemoryLimits limits) {
        return JffiNativeMachineFactory.createMemory(limits);
    }

    @Override
    public TableInstance createImportTable(Table table, int initValue) {
        return JffiNativeMachineFactory.createImportTable(table, initValue);
    }

    @Override
    public GlobalInstance createImportGlobal(Value value, MutabilityType mutability) {
        return JffiNativeMachineFactory.createImportGlobal(value.raw(), value.type(), mutability);
    }

    @Override
    public int priority() {
        return 50;
    }
}
