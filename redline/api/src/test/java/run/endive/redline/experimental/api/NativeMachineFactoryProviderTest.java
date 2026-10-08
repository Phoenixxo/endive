package run.endive.redline.experimental.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.Value;

/**
 * The Panama runner is compiled for JDK 25, so on an older JDK its provider class cannot
 * be loaded. With it on the classpath next to the JFFI runner, discovery has to skip it
 * rather than fail.
 */
public class NativeMachineFactoryProviderTest {

    private static final String TOO_NEW = "run.endive.redline.experimental.api.TooNewProvider";

    @Test
    public void skipsAProviderCompiledForANewerJdk(@TempDir Path dir) throws Exception {
        // A class file this JDK refuses to load. On the classpath ServiceLoader loads the
        // class in hasNext(), so this fails there and not in next().
        byte[] bytes;
        try (InputStream in =
                LowProvider.class.getResourceAsStream(
                        "/" + LowProvider.class.getName().replace('.', '/') + ".class")) {
            bytes = in.readAllBytes();
        }
        bytes[6] = (byte) 0x7F;
        bytes[7] = (byte) 0xFF;
        Path classFile = dir.resolve(TOO_NEW.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);

        // The failing provider sits between the two others, so discovery has to get past
        // it to find the one with the highest priority.
        Path services =
                dir.resolve("META-INF/services/" + NativeMachineFactoryProvider.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(
                services,
                LowProvider.class.getName()
                        + "\n"
                        + TOO_NEW
                        + "\n"
                        + HighProvider.class.getName()
                        + "\n",
                StandardCharsets.UTF_8);

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            thread.setContextClassLoader(loader);
            assertInstanceOf(
                    HighProvider.class, NativeMachineFactoryProvider.discover().orElseThrow());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static class LowProvider implements NativeMachineFactoryProvider {
        @Override
        public Instance.Builder builder(WasmModule module, NativeCode precompiledCode) {
            return null;
        }

        @Override
        public Memory createMemory(MemoryLimits limits) {
            return null;
        }

        @Override
        public TableInstance createImportTable(Table table, int initValue) {
            return null;
        }

        @Override
        public GlobalInstance createImportGlobal(Value value, MutabilityType mutability) {
            return null;
        }

        @Override
        public int priority() {
            return 1;
        }
    }

    public static class HighProvider extends LowProvider {
        @Override
        public int priority() {
            return 2;
        }
    }
}
