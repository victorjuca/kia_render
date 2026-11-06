package kia.claude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// ====================================================================================================
//  CONTENEDOR .KFN
// ====================================================================================================
final class Kfn {
    static final class Tag { String tag; int typ; long val; byte[] bytes; Tag(String t, int ty, long v, byte[] b) { tag = t; typ = ty; val = v; bytes = b; } }
    static final class Entry { String name; int type; long enc; byte[] data; Entry(String n, int t, byte[] d) { name = n; type = t; data = d; } }
    static final class Parsed { List<Tag> header = new ArrayList<>(); List<Entry> ents = new ArrayList<>(); }

    static Parsed parse(byte[] d) throws IOException {
        if (d.length < 8 || d[0] != 'K' || d[1] != 'F' || d[2] != 'N' || d[3] != 'B') throw new IOException("No es un archivo .kfn valido");
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN); b.position(4); Parsed r = new Parsed();
        while (true) {
            byte[] tg = new byte[4]; b.get(tg); String tag = new String(tg, StandardCharsets.ISO_8859_1); int typ = b.get() & 0xFF;
            if (typ == 1) r.header.add(new Tag(tag, 1, b.getInt() & 0xFFFFFFFFL, null));
            else if (typ == 2) { int n = b.getInt(); byte[] v = new byte[n]; b.get(v); r.header.add(new Tag(tag, 2, 0, v)); }
            else throw new IOException("Campo desconocido " + tag + " (tipo " + typ + ")");
            if (tag.equals("ENDH")) break;
        }
        int n = b.getInt(); long[][] meta = new long[n][]; String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            int nl = b.getInt(); byte[] nm = new byte[nl]; b.get(nm); names[i] = new String(nm, StandardCharsets.UTF_8);
            meta[i] = new long[]{b.getInt() & 0xFFFFFFFFL, b.getInt() & 0xFFFFFFFFL, b.getInt() & 0xFFFFFFFFL, b.getInt() & 0xFFFFFFFFL, b.getInt() & 0xFFFFFFFFL}; // type, olen, off, elen, enc
        }
        int base = b.position();
        for (int i = 0; i < n; i++) {
            if (meta[i][4] != 0) throw new IOException("'" + names[i] + "' esta cifrado; este estudio aun no lo soporta");
            int off = base + (int) meta[i][2], len = (int) meta[i][3];
            r.ents.add(new Entry(names[i], (int) meta[i][0], Arrays.copyOfRange(d, off, off + len)));
        }
        return r;
    }
    static List<Tag> defaultHeader() {
        List<Tag> h = new ArrayList<>();
        Object[][] v = {{"DIFM", 0L}, {"DIFW", 0L}, {"GNRE", 0xFFFFFFFFL}, {"SFTV", 18110997L}, {"MUSL", 0L}, {"ANME", 13L}, {"TYPE", 0L}};
        for (Object[] o : v) h.add(new Tag((String) o[0], 1, (Long) o[1], null));
        h.add(new Tag("FLID", 2, 0, new byte[16])); h.add(new Tag("SORC", 2, 0, new byte[0]));
        h.add(new Tag("RGHT", 1, 0, null)); h.add(new Tag("PROV", 1, 0, null));
        byte[] sp = new byte[16]; Arrays.fill(sp, (byte) ' '); h.add(new Tag("IDUS", 2, 0, sp)); h.add(new Tag("ENDH", 1, 0xFFFFFFFFL, null));
        return h;
    }
    static byte[] build(List<Tag> header, List<Entry> files) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            o.write(new byte[]{'K', 'F', 'N', 'B'});
            for (Tag t : header) {
                o.write(t.tag.getBytes(StandardCharsets.ISO_8859_1)); o.write(t.typ);
                if (t.typ == 1) o.write(le((int) t.val)); else { o.write(le(t.bytes.length)); o.write(t.bytes); }
            }
            o.write(le(files.size())); int off = 0;
            for (Entry f : files) {
                byte[] nm = f.name.getBytes(StandardCharsets.UTF_8); int n = f.data.length;
                o.write(le(nm.length)); o.write(nm); o.write(le(f.type)); o.write(le(n)); o.write(le(off)); o.write(le(n)); o.write(le(0)); off += n;
            }
            for (Entry f : files) o.write(f.data);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return o.toByteArray();
    }
    static byte[] le(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }
}
