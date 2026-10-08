package run.endive.redline.experimental.api.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

public class NativeCodeCheckTest {

    private static final WasmModule EMPTY_MODULE =
            Parser.parse(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00});

    private static String hostTriple() {
        var host = RedlineTarget.detectHost();
        assumeTrue(host.isPresent(), "Host is not a Redline target");
        return host.get().triple();
    }

    private static NativeCode code(String triple, int bodies, int imports) {
        return new NativeCode(
                triple,
                new byte[16],
                new int[bodies],
                new int[bodies],
                new int[imports],
                new int[imports]);
    }

    @Test
    public void matchingCodeIsAccepted() {
        var code = code(hostTriple(), 0, 0);

        assertSame(code, NativeCodeCheck.check(code, EMPTY_MODULE));
    }

    @Test
    public void missingCodeIsRejected() {
        assertThrows(WasmEngineException.class, () -> NativeCodeCheck.check(null, EMPTY_MODULE));
    }

    @Test
    public void codeForAnotherTargetIsRejected() {
        String other =
                hostTriple().equals(RedlineTarget.LINUX_X86_64.triple())
                        ? RedlineTarget.LINUX_AARCH64.triple()
                        : RedlineTarget.LINUX_X86_64.triple();

        var e =
                assertThrows(
                        WasmEngineException.class,
                        () -> NativeCodeCheck.check(code(other, 0, 0), EMPTY_MODULE));
        assertTrue(e.getMessage().contains(other), e.getMessage());
    }

    @Test
    public void codeForAnotherModuleIsRejected() {
        String host = hostTriple();

        assertThrows(
                WasmEngineException.class,
                () -> NativeCodeCheck.check(code(host, 1, 0), EMPTY_MODULE));
        assertThrows(
                WasmEngineException.class,
                () -> NativeCodeCheck.check(code(host, 0, 1), EMPTY_MODULE));
    }
}
