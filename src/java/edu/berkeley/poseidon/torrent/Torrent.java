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
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

import edu.berkeley.poseidon.util.FileInputStreamIterator;

/**
 * An immutable representation of a BitTorrent (.torrent) file according to
 * <a href="http://wiki.theory.org/BitTorrentSpecification">the informal
 * specification</a>. Subclasses may specify additional fields to represent
 * extensions to the BitTorrent standard.
 * <p>
 * Currently, only single-file torrents are supported, although this is simply
 * due to an incomplete implementation.
 *
 * @author James Ide
 */
public class Torrent {

    public static final String DEFAULT_CREATOR = "UCB Poseidon/0.1";
    public static final String DEFAULT_ENCODING = "UTF-8";

    private ImmutableList<ImmutableList<String>> announceList;
    private long creationDate;
    private String comment;
    private String creator;
    private String encoding;

    private int pieceLength;
    private byte[] pieceHashes;
    private boolean privateTracker;

    private String name;
    private long length;

    private Torrent(Builder builder) {
        announceList = ImmutableList.copyOf(Lists.transform(
            builder.announceList,
            new Function<List<String>, ImmutableList<String>>() {
                @Override
                public ImmutableList<String> apply(List<String> list) {
                    return ImmutableList.copyOf(list);
                }
        }));
        creationDate = firstNonNull(builder.creationDate,
                                    System.currentTimeMillis() / 1000);
        comment = builder.comment;
        creator = firstNonNull(builder.creator, DEFAULT_CREATOR);
        encoding = firstNonNull(builder.encoding, DEFAULT_ENCODING);

        pieceLength = builder.pieceLength;
        pieceHashes = builder.pieceHashes;
        privateTracker = builder.privateTracker;

        // TODO: Support multi-file torrents.
        name = builder.name;
        length = builder.length;
    }

    public String getAnnounce() {
        return announceList.get(0).get(0);
    }

    public ImmutableList<ImmutableList<String>> getAnnounceList() {
        return announceList;
    }

    public long getCreationDate() {
        return creationDate;
    }

    public String getComment() {
        return comment;
    }

    public String getCreator() {
        return creator;
    }

    public String getEncoding() {
        return encoding;
    }

    public int getPieceLength() {
        return pieceLength;
    }

    public byte[] getPieceHashes() {
        return pieceHashes;
    }

    public boolean isPrivate() {
        return privateTracker;
    }

    public String getName() {
        return name;
    }

    public long getLength() {
        return length;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("announce-list", getAnnounceList())
            .add("creation date", getCreationDate())
            .add("comment", getComment())
            .add("encoding", getEncoding())
            .add("piece length", getPieceLength())
            .add("pieces", getPieceHashes())
            .add("private", isPrivate() ? "1" : "0")
            .add("name", getName())
            .add("length", getLength())
            .toString();
    }

    public static class Builder {

        protected List<List<String>> announceList = Lists.newArrayList();
        protected Long creationDate;
        protected String comment;
        protected String creator = DEFAULT_CREATOR;
        protected String encoding = DEFAULT_ENCODING;
        protected int pieceLength;
        protected byte[] pieceHashes;
        protected boolean privateTracker;
        protected String name;
        protected long length;

        public Builder addAnnounceUri(URI uri) {
            checkNotNull(uri);
            announceList.add(ImmutableList.of(uri.toString()));
            return this;
        }

        public Builder addAnnounceUriGroup(List<URI> uris) {
            checkNotNull(uris);
            checkArgument(!uris.isEmpty(), "announce group cannot be empty");
            for (URI uri : uris) {
                checkNotNull(uri);
            }
            announceList.add(ImmutableList.copyOf(
                Lists.transform(uris, Functions.toStringFunction())));
            return this;
        }

        public Builder setCreationDate(long creationDate) {
            this.creationDate = creationDate;
            return this;
        }

        public Builder setComment(String comment) {
            this.comment = comment;
            return this;
        }

        public Builder setCreator(String creator) {
            this.creator = creator;
            return this;
        }

        public Builder setEncoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder setPieceLength(int pieceLength) {
            checkArgument(pieceLength > 0);
            this.pieceLength = pieceLength;
            return this;
        }

        public Builder setPieceHashes(byte[] pieceHashes) {
            checkNotNull(pieceHashes);
            this.pieceHashes = pieceHashes;
            return this;
        }

        public Builder setPrivate(boolean privateTracker) {
            this.privateTracker = privateTracker;
            return this;
        }

        public Builder setName(String name) {
            checkNotNull(name);
            this.name = name;
            return this;
        }

        public Builder setLength(long length) {
            checkArgument(length > 0);
            this.length = length;
            return this;
        }

        /**
         * Builds a new {@code Torrent} object with the fields specified by
         * this {@code Builder}.
         *
         * @throws TorrentException if an error occurs while building the
         *         {@code Torrent} object
         */
        public Torrent build() throws TorrentException {
            checkState(!announceList.isEmpty(),
                       "at least one announce URI must be specified");
            checkState(pieceLength > 0, "piece length must be specified");
            checkNotNull(pieceHashes, "piece hashes must be specified");
            checkNotNull(name, "file name must be specified");
            checkState(length > 0, "file length must be specified");
            return new Torrent(this);
        }
    }

    /**
     * A {@code Builder} that constructs {@code Torrent} objects based on
     * files. Instances of this class read in files on disk and com
     *
     * @author James Ide
     */
    public static class PieceHasher extends Builder {

        public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;

        private final MessageDigest sha1;

        protected List<File> files = Lists.newArrayList();

        /**
         * Constructs a new object for building {@code Torrent} objects from a
         * set of files.
         *
         * @throws TorrentException if the {@code PieceHasher} fails to
         *         initialize
         */
        public PieceHasher() throws TorrentException {
            try {
                sha1 = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new TorrentException("failed to create SHA-1 digest");
            }
        }

        public PieceHasher addFile(File file) {
            checkNotNull(file);
            if (files.size() >= 1) {
                throw new UnsupportedOperationException(
                    "multi-file torrents not supported");
            }
            files.add(file);
            return this;
        }

        /**
         * Builds a new {@code Torrent} object for seeding the files of this
         * {@code PieceHasher} instance. This methods computes the hashes of
         * the pieces of the files to seed and therefore involves reading from
         * the filesystem. Thus, this method blocks until all of the files are
         * successfully read and the pieces are hashed.
         *
         * @throws TorrentException if an error occurred while reading the file
         *         pieces
         */
        @Override
        public Torrent build() throws TorrentException {
            checkState(!files.isEmpty());
            if (pieceLength <= 0) {
                setPieceLength(DEFAULT_PIECE_LENGTH);
            }

            try {
                setPieceHashes(computePieceHashes());
            } catch (IOException e) {
                throw new TorrentException(e);
            }

            File file = files.get(0);
            setName(file.getName());
            setLength(file.length());

            return super.build();
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
                if (bytesRead > 0) {
                    sha1.update(piece, 0, bytesRead);
                    hashes.write(sha1.digest());
                }
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
            checkState(totalBytesRead <= pieceLength,
                       "read more bytes than piece length");
            return totalBytesRead;
        }

        @Override
        public PieceHasher addAnnounceUri(URI uri) {
            super.addAnnounceUri(uri);
            return this;
        }

        @Override
        public PieceHasher addAnnounceUriGroup(List<URI> uris) {
            super.addAnnounceUriGroup(uris);
            return this;
        }

        @Override
        public PieceHasher setCreationDate(long creationDate) {
            super.setCreationDate(creationDate);
            return this;
        }

        @Override
        public PieceHasher setComment(String comment) {
            super.setComment(comment);
            return this;
        }

        @Override
        public PieceHasher setCreator(String creator) {
            super.setCreator(creator);
            return this;
        }

        @Override
        public PieceHasher setEncoding(String encoding) {
            super.setEncoding(encoding);
            return this;
        }

        @Override
        public PieceHasher setPieceLength(int pieceLength) {
            super.setPieceLength(pieceLength);
            return this;
        }

        @Override
        public PieceHasher setPieceHashes(byte[] pieceHashes) {
            super.setPieceHashes(pieceHashes);
            return this;
        }

        @Override
        public PieceHasher setPrivate(boolean privateTracker) {
            super.setPrivate(privateTracker);
            return this;
        }

        @Override
        public PieceHasher setName(String name) {
            super.setName(name);
            return this;
        }

        @Override
        public PieceHasher setLength(long length) {
            super.setLength(length);
            return this;
        }
    }
}
