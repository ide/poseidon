package edu.berkeley.poseidon.torrent;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class TorrentMaker {

    public static final String DEFAULT_COMMENT = "";
    public static final String DEFAULT_CREATOR = "UCB Poseidon/0.1";
    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;

    private final Bencoder bencoder;
    private final MessageDigest sha1;

    private List<List<String>> announceList;
    private String comment;
    private String creator;
    private String encoding;
    private int pieceLength;
    private boolean privateTracker;
    private List<File> files;

    public TorrentMaker(Bencoder bencoder) {
        this.bencoder = bencoder;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("failed to create SHA-1 digest");
        }

        announceList = Lists.newArrayList();
        comment = DEFAULT_COMMENT;
        creator = DEFAULT_CREATOR;
        pieceLength = DEFAULT_PIECE_LENGTH;
        files = new ArrayList<File>();
    }

    public TorrentMaker addAnnounceUri(URI uri) {
        checkNotNull(uri);
        announceList.add(ImmutableList.of(uri.toString()));
        return this;
    }

    public TorrentMaker addAnnounceUriGroup(List<URI> uris) {
        checkNotNull(uris);
        checkArgument(!uris.isEmpty(), "announce group cannot be empty");
        for (URI uri : uris) {
            checkNotNull(uri);
        }
        announceList.add(ImmutableList.copyOf(
            Lists.transform(uris, Functions.toStringFunction())));
        return this;
    }

    public TorrentMaker setComment(String comment) {
        this.comment = comment;
        return this;
    }

    public TorrentMaker setCreator(String creator) {
        this.creator = creator;
        return this;
    }

    public TorrentMaker setPieceLength(int pieceLength) {
        checkArgument(pieceLength > 0);
        this.pieceLength = pieceLength;
        return this;
    }

    public TorrentMaker setPrivate(boolean privateTracker) {
        this.privateTracker = privateTracker;
        return this;
    }

    public TorrentMaker addFile(File file) {
        checkNotNull(file);
        if (files.size() >= 1) {
            throw new UnsupportedOperationException(
                "multi-file torrents not supported");
        }
        files.add(file);
        return this;
    }

    public byte[] encode() throws IOException {
        checkState(!files.isEmpty(), "at least one file must be shared");
        checkState(!announceList.isEmpty(),
            "at least one announce URI must be specified");

        Map<String, Object> metainfo = Maps.newHashMap();
        metainfo.put("info", buildInfoDictionary());
        metainfo.put("announce", announceList.get(0).get(0));
        metainfo.put("announce-list", announceList);
        metainfo.put("creation date", System.currentTimeMillis() / 1000);
        metainfo.put("comment", firstNonNull(comment, DEFAULT_COMMENT));
        metainfo.put("created by", firstNonNull(creator, DEFAULT_CREATOR));
        metainfo.put("encoding", firstNonNull(encoding, DEFAULT_ENCODING));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bencoder.encode(metainfo, out);
        return out.toByteArray();
    }

    private Map<String, ?> buildInfoDictionary() throws IOException {
        Map<String, Object> info = Maps.newHashMap();
        info.put("piece length", pieceLength);
        info.put("pieces", computePieceHashes());
        info.put("private", privateTracker ? "1" : "0");

        // TODO: Support multi-file torrents.
        File file = files.get(0);
        info.put("name", file.getName());
        info.put("length", file.length());

        return info;
    }
    
    private byte[] computePieceHashes() throws IOException {
        // The files are treated as a sequential stream of bytes.
        InputStream in = new SequenceInputStream(Iterators.asEnumeration(
            new FileInputStreamIterator(files)));
        // The piece hashes are concatenated together in a single stream.
        ByteArrayOutputStream hashes = new ByteArrayOutputStream();

        byte[] piece = new byte[pieceLength];
        int bytesRead;
        do {
            bytesRead = readPiece(in, piece);
            sha1.update(piece, 0, bytesRead);
            hashes.write(sha1.digest());
        } while (bytesRead == pieceLength);

        in.close();
        return hashes.toByteArray();
    }

    private int readPiece(InputStream in, byte[] buffer)
            throws IOException {
        checkPositionIndex(pieceLength, buffer.length);
        int lastBytesRead = 0;
        int totalBytesRead = 0;
        do {
            int remaining = pieceLength - totalBytesRead;
            lastBytesRead = in.read(buffer, totalBytesRead, remaining);
            if (lastBytesRead > 0) {
                totalBytesRead += lastBytesRead;
            }
        } while ((totalBytesRead < pieceLength) && (lastBytesRead != -1));
        assert totalBytesRead <= pieceLength :
            "read more bytes than piece length";
        return totalBytesRead;
    }
}
