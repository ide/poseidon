package edu.berkeley.poseidon.torrent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import com.google.inject.internal.Maps;

public class TorrentEncoder {

    private final Bencoder bencoder;

    public TorrentEncoder(Bencoder bencoder) {
        this.bencoder = bencoder;
    }

    public byte[] encode(Torrent torrent) throws IOException {
        Map<String, Object> metainfo = Maps.newHashMap();
        metainfo.put("info", buildInfoDictionary(torrent));
        metainfo.put("announce", torrent.getAnnounce());
        metainfo.put("announce-list", torrent.getAnnounceList());
        metainfo.put("creation date", torrent.getCreationDate());
        if (torrent.getComment() != null) {
            metainfo.put("comment", torrent.getComment());
        }
        if (torrent.getCreator() != null) {
            metainfo.put("created by", torrent.getCreator());
        }
        if (torrent.getEncoding() != null) {
            metainfo.put("encoding", torrent.getEncoding());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bencoder.encode(metainfo, out);
        return out.toByteArray();
    }

    private Map<String, ?> buildInfoDictionary(Torrent torrent)
            throws IOException {
        Map<String, Object> info = Maps.newHashMap();
        info.put("piece length", torrent.getPieceLength());
        info.put("pieces", torrent.getPieceHashes());
        info.put("private", torrent.isPrivate() ? "1" : "0");

        // Currently, only single-file torrents are supported.
        info.put("name", torrent.getName());
        info.put("length", torrent.getLength());

        return info;
    }
}
