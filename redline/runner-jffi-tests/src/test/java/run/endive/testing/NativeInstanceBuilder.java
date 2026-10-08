package run.endive.testing;

import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.WasmModule;

public final class NativeInstanceBuilder {

    private final JffiNativeMachineFactory.Builder delegate;

    private NativeInstanceBuilder(JffiNativeMachineFactory.Builder delegate) {
        this.delegate = delegate;
    }

    public static NativeInstanceBuilder builder(WasmModule module) {
        var b = JffiNativeMachineFactory.builder(module);
        b.withPrecompiledCode(
                NativeCompiler.compile(RedlineTarget.detectHost().orElseThrow().triple(), module));
        return new NativeInstanceBuilder(b);
    }

    public NativeInstanceBuilder withImportValues(ImportValues importValues) {
        delegate.withImportValues(importValues);
        return this;
    }

    public NativeInstanceBuilder withStart(boolean start) {
        delegate.withStart(start);
        return this;
    }

    public Instance build() {
        return delegate.build();
    }
}
