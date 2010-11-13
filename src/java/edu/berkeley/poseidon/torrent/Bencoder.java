package edu.berkeley.poseidon.torrent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.primitives.UnsignedBytes;

public class Bencoder {

    private final Charset utf8;
    private final Charset ascii;

    public Bencoder() {
        utf8 = Charset.forName("UTF-8");
        ascii = Charset.forName("US-ASCII");
    }

    public void encode(byte[] bytes, OutputStream out) throws IOException {
        Preconditions.checkNotNull(bytes);
        out.write(Integer.toString(bytes.length).getBytes(ascii));
        out.write(':');
        out.write(bytes);
    }

    public void encode(String string, OutputStream out) throws IOException {
        Preconditions.checkNotNull(string);
        encode(stringToBytes(string), out);
    }

    public void encode(long value, OutputStream out) throws IOException {
        out.write('i');
        out.write(Long.toString(value).getBytes(ascii));
        out.write('e');
    }

    public void encode(List<?> list, OutputStream out) throws IOException {
        Preconditions.checkNotNull(list);
        out.write('l');
        for (Object item : list) {
            encode(item, out);
        }
        out.write('e');
    }

    public void encode(Map<String, ?> map, OutputStream out)
            throws IOException {    
        Preconditions.checkNotNull(map);
        Map<byte[], Object> sortedMap =
            Maps.newTreeMap(UnsignedBytes.lexicographicalComparator());
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            sortedMap.put(stringToBytes(entry.getKey()), entry.getValue());
        }

        out.write('d');
        for (Map.Entry<byte[], ?> entry : sortedMap.entrySet()) {
            encode(entry.getKey(), out);
            encode(entry.getValue(), out);
        }
        out.write('e');
    }

    @SuppressWarnings("unchecked")
    private void encode(Object obj, OutputStream out) throws IOException {
        Preconditions.checkNotNull(obj);
        if (obj instanceof byte[]) {
            encode((byte[]) obj, out);
        } else if (obj instanceof String) {
            encode((String) obj, out);
        } else if (obj instanceof Number) {
            encode(((Number) obj).longValue(), out);
        } else if (obj instanceof List) {
            encode((List<?>) obj, out);
        } else if (obj instanceof Map) {
            encode((Map<String, ?>) obj, out);
        } else {
            String className = obj.getClass().getCanonicalName();
            throw new IOException("cannot bencode object of type " + className);
        }
    }

    private byte[] stringToBytes(String string) {
        return string.getBytes(utf8);
    }
}
