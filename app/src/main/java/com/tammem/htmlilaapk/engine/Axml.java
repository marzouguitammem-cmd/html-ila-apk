package com.tammem.htmlilaapk.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal editor for Android binary XML (the compiled AndroidManifest.xml inside an APK).
 *
 * It can swap whole strings in the string pool (package name, label, version name) and
 * change integer attributes such as versionCode. Every other chunk is copied byte for byte.
 */
public final class Axml {

    static final int RES_XML_TYPE = 0x0003;
    static final int RES_STRING_POOL_TYPE = 0x0001;
    static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    static final int UTF8_FLAG = 1 << 8;
    static final int SORTED_FLAG = 1;
    static final int TYPE_INT_DEC = 0x10;

    private final byte[] xmlHeader;      // RES_XML_TYPE header (usually 8 bytes)
    private final byte[] poolHeaderRest; // pool header bytes after the standard 28
    private final List<String> strings;
    private final int poolFlags;
    private final int styleCount;
    private final byte[] styleOffsets;
    private final byte[] styleData;
    private final byte[] rest;           // every chunk after the string pool

    private Axml(byte[] xmlHeader, byte[] poolHeaderRest, List<String> strings, int poolFlags,
                 int styleCount, byte[] styleOffsets, byte[] styleData, byte[] rest) {
        this.xmlHeader = xmlHeader;
        this.poolHeaderRest = poolHeaderRest;
        this.strings = strings;
        this.poolFlags = poolFlags;
        this.styleCount = styleCount;
        this.styleOffsets = styleOffsets;
        this.styleData = styleData;
        this.rest = rest;
    }

    public static Axml parse(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int type = b.getShort(0) & 0xFFFF;
        int headerSize = b.getShort(2) & 0xFFFF;
        int total = b.getInt(4);
        if (type != RES_XML_TYPE || headerSize < 8 || total > data.length) {
            throw new IllegalArgumentException("Not a binary XML file");
        }
        int p = headerSize;
        if ((b.getShort(p) & 0xFFFF) != RES_STRING_POOL_TYPE) {
            throw new IllegalArgumentException("String pool not found");
        }
        int poolHeaderSize = b.getShort(p + 2) & 0xFFFF;
        int poolSize = b.getInt(p + 4);
        int stringCount = b.getInt(p + 8);
        int styleCount = b.getInt(p + 12);
        int flags = b.getInt(p + 16);
        int stringsStart = b.getInt(p + 20);
        int stylesStart = b.getInt(p + 24);
        boolean utf8 = (flags & UTF8_FLAG) != 0;

        List<String> strings = new ArrayList<>(stringCount);
        int offsets = p + poolHeaderSize;
        for (int i = 0; i < stringCount; i++) {
            int at = p + stringsStart + b.getInt(offsets + i * 4);
            strings.add(utf8 ? readUtf8(data, at) : readUtf16(b, at));
        }

        byte[] styleOffsets = new byte[styleCount * 4];
        System.arraycopy(data, offsets + stringCount * 4, styleOffsets, 0, styleOffsets.length);
        byte[] styleData = new byte[0];
        if (styleCount > 0 && stylesStart > 0) {
            styleData = new byte[poolSize - stylesStart];
            System.arraycopy(data, p + stylesStart, styleData, 0, styleData.length);
        }

        byte[] xmlHeader = new byte[headerSize];
        System.arraycopy(data, 0, xmlHeader, 0, headerSize);
        byte[] poolHeaderRest = new byte[Math.max(0, poolHeaderSize - 28)];
        System.arraycopy(data, p + 28, poolHeaderRest, 0, poolHeaderRest.length);
        int restStart = p + poolSize;
        byte[] rest = new byte[total - restStart];
        System.arraycopy(data, restStart, rest, 0, rest.length);
        return new Axml(xmlHeader, poolHeaderRest, strings, flags, styleCount, styleOffsets, styleData, rest);
    }

    public List<String> strings() {
        return strings;
    }

    /** Replaces every string that equals a key exactly. Returns how many were replaced. */
    public int replaceStrings(Map<String, String> replacements) {
        int n = 0;
        for (int i = 0; i < strings.size(); i++) {
            String r = replacements.get(strings.get(i));
            if (r != null) {
                strings.set(i, r);
                n++;
            }
        }
        return n;
    }

    /**
     * Sets an integer attribute (by local name, e.g. "versionCode") on the first element
     * with the given name. Returns true if the attribute was found.
     */
    public boolean setIntAttribute(String element, String attribute, int value) {
        ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int p = 0;
        while (p + 8 <= rest.length) {
            int type = b.getShort(p) & 0xFFFF;
            int headerSize = b.getShort(p + 2) & 0xFFFF;
            int size = b.getInt(p + 4);
            if (size <= 0) break;
            if (type == RES_XML_START_ELEMENT_TYPE) {
                int ext = p + headerSize;
                int nameIdx = b.getInt(ext + 4);
                int attrStart = b.getShort(ext + 8) & 0xFFFF;
                int attrSize = b.getShort(ext + 10) & 0xFFFF;
                int attrCount = b.getShort(ext + 12) & 0xFFFF;
                if (element.equals(str(nameIdx))) {
                    for (int i = 0; i < attrCount; i++) {
                        int a = ext + attrStart + i * attrSize;
                        if (attribute.equals(str(b.getInt(a + 4)))) {
                            b.putInt(a + 8, -1);                 // raw value: none
                            b.putShort(a + 12, (short) 8);       // typed value size
                            b.put(a + 14, (byte) 0);
                            b.put(a + 15, (byte) TYPE_INT_DEC);
                            b.putInt(a + 16, value);
                            return true;
                        }
                    }
                    return false;
                }
            }
            p += size;
        }
        return false;
    }

    private String str(int idx) {
        return idx >= 0 && idx < strings.size() ? strings.get(idx) : null;
    }

    public byte[] toBytes() {
        boolean utf8 = (poolFlags & UTF8_FLAG) != 0;
        ByteArrayOutputStream stringData = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = stringData.size();
            byte[] enc = utf8 ? encodeUtf8(strings.get(i)) : encodeUtf16(strings.get(i));
            stringData.write(enc, 0, enc.length);
        }
        while (stringData.size() % 4 != 0) stringData.write(0);

        int poolHeaderSize = 28 + poolHeaderRest.length;
        int stringsStart = poolHeaderSize + strings.size() * 4 + styleCount * 4;
        int stylesStart = styleCount > 0 ? stringsStart + stringData.size() : 0;
        int poolSize = stringsStart + stringData.size() + (styleCount > 0 ? styleData.length : 0);

        ByteBuffer pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN);
        pool.putShort((short) RES_STRING_POOL_TYPE);
        pool.putShort((short) poolHeaderSize);
        pool.putInt(poolSize);
        pool.putInt(strings.size());
        pool.putInt(styleCount);
        pool.putInt(poolFlags & ~SORTED_FLAG);
        pool.putInt(stringsStart);
        pool.putInt(stylesStart);
        pool.put(poolHeaderRest);
        for (int off : offsets) pool.putInt(off);
        pool.put(styleOffsets);
        pool.put(stringData.toByteArray());
        if (styleCount > 0) pool.put(styleData);

        int total = xmlHeader.length + poolSize + rest.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(xmlHeader);
        out.putInt(4, total);
        out.position(xmlHeader.length);
        out.put(pool.array());
        out.put(rest);
        return out.array();
    }

    // ------------------------------------------------------------ string codecs

    private static String readUtf16(ByteBuffer b, int at) {
        int len = b.getShort(at) & 0xFFFF;
        int p = at + 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | (b.getShort(p) & 0xFFFF);
            p += 2;
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) chars[i] = (char) (b.getShort(p + i * 2) & 0xFFFF);
        return new String(chars);
    }

    private static String readUtf8(byte[] d, int at) {
        int p = at;
        // UTF-16 length (ignored), then UTF-8 byte length.
        if ((d[p] & 0x80) != 0) p += 2; else p += 1;
        int len = d[p] & 0xFF;
        if ((len & 0x80) != 0) {
            len = ((len & 0x7F) << 8) | (d[p + 1] & 0xFF);
            p += 2;
        } else {
            p += 1;
        }
        return new String(d, p, len, StandardCharsets.UTF_8);
    }

    private static byte[] encodeUtf16(String s) {
        int n = s.length();
        ByteBuffer b;
        if (n > 0x7FFF) {
            b = ByteBuffer.allocate(4 + n * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) (0x8000 | (n >>> 16)));
            b.putShort((short) (n & 0xFFFF));
        } else {
            b = ByteBuffer.allocate(2 + n * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) n);
        }
        for (int i = 0; i < n; i++) b.putShort((short) s.charAt(i));
        b.putShort((short) 0);
        return b.array();
    }

    private static byte[] encodeUtf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int n = s.length();
        if (n > 0x7FFF || bytes.length > 0x7FFF) throw new IllegalArgumentException("String too long");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLen8(out, n);
        writeLen8(out, bytes.length);
        out.write(bytes, 0, bytes.length);
        out.write(0);
        return out.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream out, int len) {
        if (len > 0x7F) {
            out.write(0x80 | (len >> 8));
            out.write(len & 0xFF);
        } else {
            out.write(len);
        }
    }
}
