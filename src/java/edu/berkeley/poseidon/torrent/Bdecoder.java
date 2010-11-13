package edu.berkeley.poseidon.torrent;

import static com.google.common.base.Preconditions.checkState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.print.DocFlavor.BYTE_ARRAY;

import org.apache.cassandra.utils.Pair;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

public class Bdecoder {

    public enum Type {
        BYTE_STRING, INTEGER, LIST, DICTIONARY
    }

    private static final int PUSHBACK_BUFFER_SIZE = 256;

    private final Charset utf8;
    private final Charset ascii;

    public Bdecoder() {
        utf8 = Charset.forName("UTF-8");
        ascii = Charset.forName("US-ASCII");
    }
    
    public Pair<?, Type> decode(InputStream in) throws IOException {
        return decode(new PushbackInputStream(in, PUSHBACK_BUFFER_SIZE));
    }

    private Pair<?, Type> decode(PushbackInputStream in) throws IOException {
        int lengthPrefix = readLength(in);
        if (lengthPrefix != -1) {
            // Since the byte-string decoder will try to read the length, we
            // need to push the bytes back into the stream. Silly, I know.
            in.unread(Integer.toString(lengthPrefix).getBytes(ascii));
            return Pair.create(decodeByteString(in), Type.BYTE_STRING);
        }

        // Peek at the next character that determines the type of the object.
        int typeByte = in.read();
        if (typeByte == -1) {
            throw new IOException("no data to read from input stream");
        }
        in.unread(typeByte);

        char typePrefix = (char) typeByte;
        switch (typePrefix) {
            case 'i':
                return Pair.create(decodeInteger(in), Type.INTEGER);
            case 'l':
                return Pair.create(decodeList(in), Type.LIST);
            case 'd':
                return Pair.create(decodeDictionary(in), Type.DICTIONARY);
            default:
                throw new IOException(typePrefix + " is an unknown delimiter");
        }
    }

    public byte[] decodeByteString(PushbackInputStream in) throws IOException {
        int length = readLength(in);
        checkState(length != -1);

        byte[] buffer = new byte[length];
        int bytesRead = in.read(buffer);
        if 
    }

    public String decodeString(PushbackInputStream in) throws IOException {
        return new String(decodeByteString(in), utf8);
    }

    public Long decodeInteger(PushbackInputStream in) throws IOException {
        
    }

    public List<?> decodeList(PushbackInputStream in) throws IOException {
        
    }

    public Map<String, ?> decodeDictionary(PushbackInputStream in)
            throws IOException {
        
        Map<String, Object> dictionary = Maps.newHashMap();
        
    }

    /**
     * Reads a base-10 ASCII-encoded integer from the specified input stream
     * and pushes the bytes read back into the stream. If there is no
     * non-negative integer at the beginning of the stream, this method returns
     * -1.
     *
     * @param in the stream from which to read the integer. Any bytes consumed
     *        from the stream are pushed back into it.
     * @throws IOException if an error occurs while reading from the stream
     */
    private int peekAtLength(PushbackInputStream in) throws IOException {
    
    }

    private int readLength(PushbackInputStream in) throws IOException {
        // Read as many digits from the stream as possible.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nextByte = in.read();
        while ((nextByte != -1) && Character.isDigit((char) nextByte)) {
            buffer.write(nextByte);
            nextByte = in.read();
        }
        byte[] data = buffer.toByteArray();
        
        // Push back the last byte if we didn't reach the end of the stream.
        if (nextByte != -1) {
            in.unread(nextByte);
        }

        return (data.length > 0) ?
            Integer.valueOf(new String(data, ascii)) : -1;
    }
}
