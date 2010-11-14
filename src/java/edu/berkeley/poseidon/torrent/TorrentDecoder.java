package edu.berkeley.poseidon.torrent;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.utils.Pair;

import com.google.common.collect.Lists;

public class TorrentDecoder {

    private final Bdecoder bdecoder;

    public TorrentDecoder(Bdecoder bdecoder) {
        this.bdecoder = bdecoder;
    }

    @SuppressWarnings("unchecked")
    public Torrent decode(byte[] data) throws TorrentException {
        Pair<?, Bdecoder.Type> decodedObject = bdecoder.decode(data);
        checkState(decodedObject.right == Bdecoder.Type.DICTIONARY);
        Map<String, ?> metainfo = (Map<String, ?>) decodedObject.left;
        Torrent.Builder builder = new Torrent.Builder();

        // Add the announce URIs to the builder.
        if (metainfo.containsKey("announce-list")) {
            for (List<?> list : (List<List<?>>) metainfo.get("announce-list")) {
                List<URI> uris = Lists.newArrayList();
                for (Object item : list) {
                    try {
                        uris.add(new URI(asString(item)));
                    } catch (URISyntaxException e) {
                        throw new TorrentException(e);
                    }
                }
                builder.addAnnounceUriGroup(uris);
            }
        } else {
            checkState(metainfo.containsKey("announce"));
            try {
                builder.addAnnounceUri(
                    new URI(asString(metainfo.get("announce"))));
            } catch (URISyntaxException e) {
                throw new TorrentException(e);
            }
        }

        // Retrieve the other fields from the metainfo dictionary. 
        if (metainfo.containsKey("creation date")) {
            builder.setCreationDate(asLong(metainfo.get("creation date")));
        }
        if (metainfo.containsKey("comment")) {
            builder.setComment(asString(metainfo.get("comment")));
        }
        if (metainfo.containsKey("created by")) {
            builder.setCreator(asString(metainfo.get("created by")));
        }
        if (metainfo.containsKey("encoding")) {
            builder.setEncoding(asString(metainfo.get("encoding")));
        }

        // The info dictionary contains the details about the files themselves.
        checkState(metainfo.containsKey("info"));
        checkState(metainfo.get("info") instanceof Map);
        decodeInfoDictionary((Map<String, ?>) metainfo.get("info"), builder);

        return builder.build();
    }

    private void decodeInfoDictionary(Map<String, ?> info,
                                      Torrent.Builder builder) {
        builder.setPieceLength((int) asLong(info.get("piece length")));
        builder.setPieceHashes((byte[]) info.get("pieces"));
        builder.setPrivate(info.containsKey("private") &&
                           "1".equals(asString(info.get("private"))));

        // Currently, only single-file torrents are supported.
        builder.setName(asString(info.get("name")));
        builder.setLength(asLong(info.get("length")));
    }

    private long asLong(Object o) {
        checkNotNull(o);
        return (o instanceof Number) ? ((Number) o).longValue()
                                     : Long.valueOf(String.valueOf(o));
    }

    private String asString(Object o) {
        checkNotNull(o);
        return (o instanceof byte[]) ? bdecoder.asString((byte[]) o)
                                     : String.valueOf(o);
    }    
}
