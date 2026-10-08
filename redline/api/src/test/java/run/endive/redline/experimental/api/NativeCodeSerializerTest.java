package run.endive.redline.experimental.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class NativeCodeSerializerTest {

    private static NativeCode sample() {
        byte[] image = new byte[64];
        Arrays.fill(image, (byte) 0xCC);
        return new NativeCode(
                "x86_64-unknown-linux-gnu",
                image,
                new int[] {0, NativeCode.NOT_COMPILED, 16},
                new int[] {32, NativeCode.NOT_COMPILED, 32},
                new int[] {48},
                new int[] {50});
    }

    private static byte[] serialize(NativeCode code) throws IOException {
        var out = new ByteArrayOutputStream();
        NativeCodeSerializer.serialize(code, out);
        return out.toByteArray();
    }

    private static NativeCode deserialize(byte[] bytes) throws IOException {
        return NativeCodeSerializer.deserialize(new ByteArrayInputStream(bytes));
    }

    @Test
    public void roundTripKeepsTheLayout() throws IOException {
        var code = deserialize(serialize(sample()));

        assertEquals("x86_64-unknown-linux-gnu", code.triple());
        assertArrayEquals(sample().image(), code.image());
        assertEquals(3, code.functionBodyCount());
        assertTrue(code.isCompiled(0));
        assertFalse(code.isCompiled(1));
        assertEquals(16, code.bodyOffset(2));
        assertEquals(32, code.entryTrampolineOffset(2));
        assertEquals(1, code.importCount());
        assertEquals(48, code.importTrampolineOffset(0));
        assertEquals(50, code.importStubSlotOffset(0));
    }

    @Test
    public void aVersionOneFileIsRejected() throws IOException {
        var out = new ByteArrayOutputStream();
        var dos = new DataOutputStream(out);
        dos.writeInt(0x434C344A);
        dos.writeInt(1);
        dos.writeInt(0);

        var e = assertThrows(IOException.class, () -> deserialize(out.toByteArray()));
        assertTrue(e.getMessage().contains("version"), e.getMessage());
    }

    @Test
    public void aTruncatedFileIsRejected() throws IOException {
        byte[] bytes = serialize(sample());

        assertThrows(IOException.class, () -> deserialize(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IOException.class, () -> deserialize(Arrays.copyOf(bytes, 40)));
    }

    @Test
    public void anOffsetOutsideTheImageIsRejected() throws IOException {
        byte[] bytes = serialize(sample());
        // the last int is the import stub slot offset, which must leave room for 8 bytes
        bytes[bytes.length - 1] = (byte) 57;

        var e = assertThrows(IOException.class, () -> deserialize(bytes));
        assertTrue(e.getMessage().contains("import stub slot 0"), e.getMessage());
    }

    @Test
    public void aBodyWithoutItsEntryTrampolineIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NativeCode(
                                "x86_64-unknown-linux-gnu",
                                new byte[16],
                                new int[] {0},
                                new int[] {NativeCode.NOT_COMPILED},
                                new int[0],
                                new int[0]));
    }
}
