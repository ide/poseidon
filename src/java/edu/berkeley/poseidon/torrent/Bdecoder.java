package edu.berkeley.poseidon.torrent;

import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkState;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.utils.Pair;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class Bdecoder {

    public class BdecoderException extends RuntimeException {
        public BdecoderException(String str, RuntimeException cause) {
            super(str, cause);
        }
    }

    public enum Type {
        BYTE_STRING, INTEGER, LIST, DICTIONARY
    }

    private final Charset utf8;
    private final Charset ascii;

    private int offset;

    public Bdecoder() {
        utf8 = Charset.forName("UTF-8");
        ascii = Charset.forName("US-ASCII");
    }

    public Pair<?, Type> decode(byte[] data) {
        offset = 0;
        try {
            return decodeInternal(data);
        } catch (RuntimeException e) {
            int i = offset - 5;
            StringBuilder toPrint = new StringBuilder();
            if (i <= 0) {
                i = 0;
                toPrint.append(" ^");
            }
            for (; i < offset + 5; i++) {
                if (i >= data.length) {
                    toPrint.append(" $");
                    break;
                }
                toPrint.append(" ");
                toPrint.append(((int)(data[i])) & 0xff);
            }
/* // For debugging invalid torrent files.
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream("/tmp/bdecoder_"+Math.random()+".torrent");
                fos.write(data);
                fos.close();
            } catch (java.io.IOException ioex) {
            }
*/
            throw new BdecoderException("Failed to decode at position "+offset+" out of "+data.length+":"+toPrint, e);
        }
    }

    public String asString(byte[] data) {
        checkNotNull(data);
        return new String(data, utf8);
    }
    
    private Pair<?, Type> decodeInternal(byte[] data) {
        checkElementIndex(offset, data.length);
        switch (data[offset]) {
            case 'i':
                return Pair.create(decodeInteger(data), Type.INTEGER);
            case 'l':
                return Pair.create(decodeList(data), Type.LIST);
            case 'd':
                return Pair.create(decodeDictionary(data), Type.DICTIONARY);
            default:
                int originalOffset = offset;
                int lengthPrefix = readLength(data);
                if (lengthPrefix != -1) {
                    // Since the byte-string decoder will try to read the
                    // length, we need to restore the offset into the array.
                    offset = originalOffset;
                    return Pair.create(decodeByteString(data),
                                       Type.BYTE_STRING);
                }

                String error = String.format("'%c' is an unknown delimiter",
                                             data[offset]);
                throw new RuntimeException(error);
        }
    }

    private byte[] decodeByteString(byte[] data) {
        int length = readLength(data);
        checkState(length != -1);
        consume(data, ':');
        checkPositionIndex(offset + length, data.length);

        byte[] byteString = Arrays.copyOfRange(data, offset, offset + length);
        offset += length;
        return byteString;
    }

    private String decodeString(byte[] data) {
        return asString(decodeByteString(data));
    }

    private long decodeInteger(byte[] data) {
        consume(data, 'i');
        int start = offset;
        while (data[offset] != 'e') {
            offset++;
        }

        byte[] slice = Arrays.copyOfRange(data, start, offset);
        consume(data, 'e');        
        return Long.valueOf(new String(slice, ascii));
    }

    private List<?> decodeList(byte[] data) {
        consume(data, 'l');
        List<Object> list = Lists.newArrayList();
        while (data[offset] != 'e') {
            list.add(decodeInternal(data).left);
        }
        consume(data, 'e');
        return list;
    }

    private Map<String, ?> decodeDictionary(byte[] data) {
        consume(data, 'd');
        Map<String, Object> dictionary = Maps.newHashMap();
        while (data[offset] != 'e') {
            // Java evaluates arguments from left to right.
            dictionary.put(decodeString(data), decodeInternal(data).left);
        }
        consume(data, 'e');
        return dictionary;
    }

    private void consume(byte[] data, char expected) {
        checkState(data[offset] == expected);
        offset++;
    }

    /**
     * Reads a base-10 ASCII-encoded integer from the specified byte array and
     * increments the offset into the array by the appropriate amount. If there
     * is no non-negative integer to read from the array, this method returns
     * -1 and the offset into the array is unchanged.
     *
     * @param data the array of bytes from which to read an integer
     */
    private int readLength(byte[] data) {
        // Read as many digits from the array as possible.
        int start = offset;
        while ((offset < data.length) &&
                Character.isDigit((char) data[offset])) {
            offset++;
        }

        if (offset == start) {
            return -1;
        }
        byte[] slice = Arrays.copyOfRange(data, start, offset);
        return Integer.valueOf(new String(slice, ascii));
    }
}
