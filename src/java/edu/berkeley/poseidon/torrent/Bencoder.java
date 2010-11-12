package edu.berkeley.poseidon.torrent;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public interface Bencoder {

    void encode(byte[] bytes, OutputStream out) throws IOException;

    void encode(String string, OutputStream out) throws IOException;

    void encode(long integer, OutputStream out) throws IOException;

    void encode(List<?> list, OutputStream out) throws IOException;

    void encode(Map<String, ?> map, OutputStream out) throws IOException;
}
