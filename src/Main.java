import sun.misc.Unsafe;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.min;

public class Main {

    static final int UUID_STR_LENGTH = 36;
    static final Unsafe unsafe;
    static {
        Unsafe theUnsafe;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            theUnsafe = (Unsafe) f.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            theUnsafe = null;
            e.printStackTrace();
        }
        unsafe = theUnsafe;
    }

    // returns a list of {offset, size} where sum of all sizes is = allocate
    private static List<int[]> offsetAndSize(int allocate, int upperBlockSize) {
        if (upperBlockSize > allocate) {
            throw new IllegalArgumentException("upperBlockSize cannot be larger than allocated bytes");
        }

        final Random random = new Random();
        List<int[]> offsetAndSize = new ArrayList<>(allocate / upperBlockSize);

        int allocated = 0;
        for (int size; allocated < allocate; ) {
            size = random.nextInt(upperBlockSize) + 1;
            size = min(allocate - allocated, size);

            offsetAndSize.add(new int[]{ allocated, size });
            allocated += size;
        }
        Collections.shuffle(offsetAndSize);
        return offsetAndSize;
    }

    private static void putString(long address, String content) {
        for (int i = 0; i < content.length(); ++i) {
            putChar(address + i, content.charAt(i));
        }
    }

    private static void putChar(long address, char ch) {
        unsafe.putChar(address, ch);
    }

    private static char getChar(long address) {
        return (char) unsafe.getByte(address);
    }

    private static long allocate(int size) {
        return unsafe.allocateMemory(size);
    }

    private static void free(long address) {
        unsafe.freeMemory(address);
    }

    private static void copy(long srcAddress, long dstAddress, long byteLen) {
        unsafe.copyMemory(srcAddress, dstAddress, byteLen);
    }

    private static String getString(long address, int byteLen) {
        StringBuilder sb = new StringBuilder();
        sb.setLength(byteLen);

        long ptr = address;
        for (int i = 0; i < byteLen; ++i) {
            sb.setCharAt(i, getChar(ptr));
            ptr += Byte.BYTES;
        }
        return sb.toString();
    }

    private static String randomString(int length) {
        return Stream.generate(() -> UUID.randomUUID().toString())
                .limit(length / UUID_STR_LENGTH + 1)
                .collect(Collectors.joining())
                .substring(0, length);
    }

    private static void assertEquals(String expected, String got) {
        if (!expected.equals(got)) {
            throw new RuntimeException(
              String.format("expected=%s, got=%s", expected, got));
        }
    }

    public static void main(String[] args) {

        final int length = 1 << 20;
        final int upperBlockSize = 1 << 5;

        final long source = allocate(length);
        final long dest = allocate(length);

        final boolean verifyWrites = true;

        String sequentialWriteStr = randomString(length);
        String randomWriteStr = randomString(length);

        List<int[]> offsetAndSize = offsetAndSize(length, upperBlockSize);

        long sequentialAccessCopyTime, randomAccessCopyTime;
        try {
            putString(source, sequentialWriteStr);
            long now = System.currentTimeMillis();

            copy(source, dest, length);
            // ensure write has happened
            if (verifyWrites) {
                String s = getString(dest, length);
                assertEquals(sequentialWriteStr, s);
            }

            sequentialAccessCopyTime = System.currentTimeMillis() - now;

            // avoid reordering
            VarHandle.fullFence();

            putString(source, randomWriteStr);
            now = System.currentTimeMillis();

            for (int[] arr : offsetAndSize) {
                copy(source + arr[0], dest + arr[0], arr[1]);
            }
            if (verifyWrites) {
                String s = getString(dest, length);
                assertEquals(randomWriteStr, s);
            }

            randomAccessCopyTime = System.currentTimeMillis() - now;
        } finally {
            free(source);
            free(dest);
        }

        System.out.printf("sequentialAccessCopyTime = %d, randomAccessCopyTime = %d\n",
                sequentialAccessCopyTime, randomAccessCopyTime);
    }
}
