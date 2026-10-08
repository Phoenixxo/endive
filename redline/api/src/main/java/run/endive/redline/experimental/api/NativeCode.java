package run.endive.redline.experimental.api;

import java.util.Objects;

/** Function bodies and trampolines of one module, compiled ahead of time for one target. */
public final class NativeCode {

    /** Offset of a function body that was not compiled. */
    public static final int NOT_COMPILED = -1;

    private final String triple;
    private final byte[] image;
    private final int[] bodyOffsets;
    private final int[] entryTrampolineOffsets;
    private final int[] importTrampolineOffsets;
    // where the runner writes each import's host stub address, in native byte order
    private final int[] importStubSlotOffsets;

    public NativeCode(
            String triple,
            byte[] image,
            int[] bodyOffsets,
            int[] entryTrampolineOffsets,
            int[] importTrampolineOffsets,
            int[] importStubSlotOffsets) {
        this.triple = Objects.requireNonNull(triple, "triple");
        this.image = Objects.requireNonNull(image, "image");
        this.bodyOffsets = bodyOffsets.clone();
        this.entryTrampolineOffsets = entryTrampolineOffsets.clone();
        this.importTrampolineOffsets = importTrampolineOffsets.clone();
        this.importStubSlotOffsets = importStubSlotOffsets.clone();

        if (this.bodyOffsets.length != this.entryTrampolineOffsets.length) {
            throw new IllegalArgumentException(
                    this.bodyOffsets.length
                            + " function bodies but "
                            + this.entryTrampolineOffsets.length
                            + " entry trampolines");
        }
        for (int i = 0; i < this.bodyOffsets.length; i++) {
            boolean compiled = this.bodyOffsets[i] != NOT_COMPILED;
            if (compiled != (this.entryTrampolineOffsets[i] != NOT_COMPILED)) {
                throw new IllegalArgumentException(
                        "Function body " + i + " and its entry trampoline disagree");
            }
            if (compiled) {
                checkOffset("function body " + i, this.bodyOffsets[i]);
                checkOffset("entry trampoline " + i, this.entryTrampolineOffsets[i]);
            }
        }
        if (this.importTrampolineOffsets.length != this.importStubSlotOffsets.length) {
            throw new IllegalArgumentException(
                    this.importTrampolineOffsets.length
                            + " import trampolines but "
                            + this.importStubSlotOffsets.length
                            + " stub slots");
        }
        for (int i = 0; i < this.importTrampolineOffsets.length; i++) {
            checkOffset("import trampoline " + i, this.importTrampolineOffsets[i]);
            checkOffset("import stub slot " + i, this.importStubSlotOffsets[i], 8);
        }
    }

    private void checkOffset(String what, int offset) {
        checkOffset(what, offset, 1);
    }

    private void checkOffset(String what, int offset, int size) {
        if (offset < 0 || offset > image.length - size) {
            throw new IllegalArgumentException(
                    what + " at " + offset + " is outside the " + image.length + " byte image");
        }
    }

    public String triple() {
        return triple;
    }

    /** The code image. Shared, not copied: callers must not modify it. */
    public byte[] image() {
        return image;
    }

    public int functionBodyCount() {
        return bodyOffsets.length;
    }

    public boolean isCompiled(int bodyIndex) {
        return bodyOffsets[bodyIndex] != NOT_COMPILED;
    }

    public int bodyOffset(int bodyIndex) {
        return bodyOffsets[bodyIndex];
    }

    public int entryTrampolineOffset(int bodyIndex) {
        return entryTrampolineOffsets[bodyIndex];
    }

    public int importCount() {
        return importTrampolineOffsets.length;
    }

    public int importTrampolineOffset(int importIndex) {
        return importTrampolineOffsets[importIndex];
    }

    public int importStubSlotOffset(int importIndex) {
        return importStubSlotOffsets[importIndex];
    }
}
