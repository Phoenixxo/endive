package run.endive.redline.experimental.runner.internal;

import static run.endive.wasm.types.Value.REF_NULL_VALUE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.runtime.Instance;
import run.endive.runtime.TableInstance;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;

/**
 * Off-heap table implementation for native compilation.
 *
 * <p>Layout: [size:i32 @ 0][max:i32 @ 4][entries... @ 8]
 *
 * <p>Each entry is 24 bytes:
 * <pre>
 *   [0..4)   i32  canonicalTypeIdx
 *   [4..8)   i32  funcId
 *   [8..16)  i64  funcPtr (native address)
 *   [16..24) i64  ownerCtx (context of the native instance defining the function)
 * </pre>
 *
 * <p>NULL entry: funcId == REF_NULL_VALUE (-1), funcPtr == 0, typeIdx == 0, ownerCtx == 0.
 *
 * <p>The type index, function id and pointer are the owning instance's,
 * which is not necessarily an instance that uses the table.
 * Native code calls an entry directly only from the owner's context.
 * An entry whose function is defined by an instance without native code has no pointer
 * and no owner context; its owner is kept here instead.
 *
 * <p>The entries array is pre-allocated to the table's max capacity so that
 * {@code TABLE.GROW} only bumps the size field without reallocation.
 * Native code reads/writes this buffer directly — no Java trampoline
 * needed for GET/SET/SIZE/GROW/FILL/COPY.
 *
 * <p>Resolution of funcId → funcPtr+typeIdx uses the {@code instance} parameter
 * passed to {@link #setRef}, obtaining the calling module's NativeMachine.
 * This ensures cross-module shared tables resolve to the correct native addresses.
 */
public final class NativeTable extends TableInstance {

    private static final int MAX_PREALLOC = 1_000_000;

    private final MemorySegment buffer;
    private final int capacity;
    private final boolean isExternRef;

    /**
     * The instance that wrote each entry from Java, for entries without an owner context:
     * one not yet resolved, or one owned by an instance without native code.
     * Grows with the table rather than to its capacity.
     */
    private Instance[] owners = new Instance[0];

    public NativeTable(Table table, int initValue, Arena arena) {
        super(table, initValue);
        this.isExternRef = table.elementType().equals(ValType.ExternRef);
        int initial = (int) table.limits().min();
        int max = (int) table.limits().max();
        // Pre-allocate to max, capped at MAX_PREALLOC
        this.capacity = (max > 0 && max <= MAX_PREALLOC) ? max : Math.max(initial, MAX_PREALLOC);
        long bufferSize =
                CtxBuffer.TABLE_ENTRIES_OFFSET + (long) capacity * CtxBuffer.TABLE_ENTRY_SIZE;
        this.buffer = arena.allocate(bufferSize, 8);

        // Write header
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET, initial);
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_MAX_OFFSET, max > 0 ? max : capacity);

        // Only the entries below size are reachable: every access bounds-checks
        // against the size field, and grow fills the slots it exposes. Filling
        // the whole capacity here would make the entire pre-allocation resident.
        for (int i = 0; i < initial; i++) {
            writeNullEntry(i);
        }

        if (initValue != REF_NULL_VALUE) {
            // The instance has no machine yet, so funcPtr cannot be resolved
            // here. resolvePendingRefs fills it in once there is one.
            for (int i = 0; i < initial; i++) {
                writeUnresolvedEntry(i, initValue, null);
            }
        }
    }

    private long entryBase(int index) {
        return CtxBuffer.TABLE_ENTRIES_OFFSET + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
    }

    private void writeNullEntry(int index) {
        writeEntry(index, 0, REF_NULL_VALUE, 0L, 0L);
    }

    /**
     * A funcref with no native address: not resolved yet,
     * or defined by an instance without native code.
     * Either way {@code owner} is the instance it names a function of.
     */
    private void writeUnresolvedEntry(int index, int value, Instance owner) {
        writeEntry(index, 0, value, 0L, 0L);
        setOwner(index, owner);
    }

    private void writeEntry(int index, int typeIdx, int funcId, long funcPtr, long ownerCtx) {
        long base = entryBase(index);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_OWNER_CTX_OFFSET, ownerCtx);
    }

    /**
     * Fills in the native address of entries written before their instance had a machine,
     * which is the case for a table initialiser.
     * An entry is resolved against the instance that wrote it,
     * or {@code instance} when nothing recorded one.
     */
    void resolvePendingRefs(Instance instance) {
        if (isExternRef) {
            return;
        }
        int sz = size();
        for (int i = 0; i < sz; i++) {
            long base = entryBase(i);
            int funcId = buffer.get(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
            long funcPtr =
                    buffer.get(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);
            if (funcId != REF_NULL_VALUE && funcPtr == 0L) {
                Instance owner = recordedOwner(i);
                resolveFromInstance(i, funcId, owner != null ? owner : instance);
            }
        }
    }

    private void writeResolvedEntry(int index, int funcId, NativeMachine owner) {
        long funcPtr = owner.getFuncTable().get(ValueLayout.JAVA_LONG, (long) funcId * 8);
        int typeIdx = owner.getFuncTypesArray().get(ValueLayout.JAVA_INT, (long) funcId * 4);
        writeEntry(index, typeIdx, funcId, funcPtr, owner.contextAddress());
    }

    /**
     * Writes {@code funcId} as a function of {@code instance}.
     * With native code it gets that instance's address and context.
     * Without, or before it has a machine,
     * it is left for {@link #resolvePendingRefs} or a call through the runner.
     */
    private void resolveFromInstance(int index, int funcId, Instance instance) {
        if (isExternRef) {
            writeEntry(index, 0, funcId, 0L, 0L);
        } else if (instance != null && instance.getMachine() instanceof NativeMachine nm) {
            writeResolvedEntry(index, funcId, nm);
        } else {
            writeUnresolvedEntry(index, funcId, instance);
        }
    }

    private Instance recordedOwner(int index) {
        return index < owners.length ? owners[index] : null;
    }

    private void setOwner(int index, Instance owner) {
        if (index >= owners.length) {
            if (owner == null) {
                return;
            }
            owners =
                    Arrays.copyOf(owners, Math.max(index + 1, Math.max(size(), owners.length * 2)));
        }
        owners[index] = owner;
    }

    /** The context of the instance owning entry {@code index}, or 0 when it has none. */
    long ownerContext(int index) {
        return buffer.get(
                ValueLayout.JAVA_LONG, entryBase(index) + CtxBuffer.ENTRY_OWNER_CTX_OFFSET);
    }

    /**
     * Copies what this table keeps outside native memory for {@code count} entries,
     * after native code copied the entries themselves.
     * Overlapping ranges are handled.
     */
    void copyOwnersFrom(NativeTable src, int srcIndex, int dstIndex, int count) {
        Instance[] from = new Instance[count];
        for (int i = 0; i < count; i++) {
            from[i] = src.recordedOwner(srcIndex + i);
        }
        for (int i = 0; i < count; i++) {
            setOwner(dstIndex + i, from[i]);
        }
    }

    /** Get the native address of the table buffer, for passing to native code. */
    MemorySegment nativeBuffer() {
        return buffer;
    }

    boolean isExternRef() {
        return isExternRef;
    }

    @Override
    public int size() {
        return buffer.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET);
    }

    @Override
    public ValType elementType() {
        return super.elementType();
    }

    @Override
    public TableLimits limits() {
        return super.limits();
    }

    @Override
    public int ref(int index) {
        if (index < 0 || index >= size()) {
            throw new WasmEngineException("undefined element");
        }
        long base = entryBase(index);
        return buffer.get(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
    }

    @Override
    public int requiredRef(int index) {
        int r = ref(index);
        if (r == REF_NULL_VALUE) {
            throw new WasmEngineException("uninitialized element " + index);
        }
        return r;
    }

    @Override
    public void setRef(int index, int value, Instance instance) {
        if (index < 0 || index >= size()) {
            throw new UninstantiableException("out of bounds table access");
        }
        if (value == REF_NULL_VALUE) {
            writeNullEntry(index);
        } else {
            resolveFromInstance(index, value, instance);
        }
    }

    @Override
    public int grow(int delta, int value, Instance instance) {
        int oldSize = size();
        int newSize = oldSize + delta;
        int max = buffer.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_MAX_OFFSET);
        if (delta < 0 || newSize > max || newSize > capacity) {
            return -1;
        }
        // Fill new slots
        for (int i = oldSize; i < newSize; i++) {
            if (value == REF_NULL_VALUE) {
                writeNullEntry(i);
            } else {
                resolveFromInstance(i, value, instance);
            }
        }
        // Update size
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET, newSize);
        limits().grow(delta);
        return oldSize;
    }

    /**
     * The instance whose function entry {@code index} holds, or {@code null} for a null entry,
     * or one whose owner was never known.
     */
    @Override
    public Instance instance(int index) {
        if (index < 0 || index >= size() || isExternRef || ref(index) == REF_NULL_VALUE) {
            return null;
        }
        long ctx = ownerContext(index);
        if (ctx != 0L) {
            NativeMachine owner = NativeMachine.forContext(ctx);
            return owner != null ? owner.instance() : null;
        }
        return recordedOwner(index);
    }

    @Override
    public void reset() {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            writeNullEntry(i);
        }
    }
}
