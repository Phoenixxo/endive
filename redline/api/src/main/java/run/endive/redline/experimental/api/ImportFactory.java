package run.endive.redline.experimental.api;

import java.util.Objects;
import run.endive.runtime.ByteBufferMemory;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.Value;

/**
 * Creates the memories, tables and globals a module imports, for whichever backend
 * is running. Obtained from the generated module's {@code imports()}.
 */
public final class ImportFactory {

    private final NativeMachineFactoryProvider provider;

    private ImportFactory(NativeMachineFactoryProvider provider) {
        this.provider = provider;
    }

    /**
     * Picks the backend for a module holding this native code, or none. Called by the
     * generated {@code imports()}.
     */
    public static ImportFactory forNativeCode(NativeCode nativeCode) {
        if (nativeCode == null) {
            return forBytecode();
        }
        return NativeMachineFactoryProvider.discover()
                .map(ImportFactory::forNative)
                .orElseGet(ImportFactory::forBytecode);
    }

    /** Builds imports the given native backend can use. */
    public static ImportFactory forNative(NativeMachineFactoryProvider provider) {
        return new ImportFactory(Objects.requireNonNull(provider, "provider"));
    }

    /** Builds the ordinary runtime imports, for when there is no native backend. */
    public static ImportFactory forBytecode() {
        return new ImportFactory(null);
    }

    /** Whether these imports are being built for natively compiled code. */
    public boolean isNative() {
        return provider != null;
    }

    public Memory memory(MemoryLimits limits) {
        if (provider != null) {
            return provider.createMemory(limits);
        }
        return new ByteBufferMemory(limits);
    }

    public TableInstance table(Table table, int initValue) {
        if (provider != null) {
            return provider.createImportTable(table, initValue);
        }
        return new TableInstance(table, initValue);
    }

    public GlobalInstance global(Value value, MutabilityType mutability) {
        if (provider != null) {
            return provider.createImportGlobal(value, mutability);
        }
        return GlobalInstance.builder().value(value).mutabilityType(mutability).build();
    }
}
