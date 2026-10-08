package run.endive.redline.experimental.api;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.Value;

public interface NativeMachineFactoryProvider {

    Instance.Builder builder(WasmModule module, NativeCode precompiledCode);

    Memory createMemory(MemoryLimits limits);

    TableInstance createImportTable(Table table, int initValue);

    GlobalInstance createImportGlobal(Value value, MutabilityType mutability);

    int priority();

    static Optional<NativeMachineFactoryProvider> discover() {
        NativeMachineFactoryProvider best = null;
        var it = ServiceLoader.load(NativeMachineFactoryProvider.class).iterator();
        while (true) {
            NativeMachineFactoryProvider provider;
            try {
                if (!it.hasNext()) {
                    break;
                }
                provider = it.next();
            } catch (ServiceConfigurationError | LinkageError e) {
                // This provider cannot be loaded on this JDK — the Panama runner is
                // compiled for 25, so loading it on an older JDK fails here.
                // Skip it and let a lower-priority provider win.
                //
                // The catch must wrap both hasNext() and next(): ServiceLoader reports
                // these failures from the iterator, and on the classpath it loads the
                // provider class in hasNext(), so a for-each loop would let them escape.
                // The failed provider has already been consumed, so this cannot spin.
                continue;
            }
            if (best == null || provider.priority() > best.priority()) {
                best = provider;
            }
        }
        return Optional.ofNullable(best);
    }
}
