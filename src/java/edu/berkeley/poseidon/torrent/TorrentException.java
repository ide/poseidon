package edu.berkeley.poseidon.torrent;

/**
 * An exception that signals that a BitTorrent-related exception has occurred.
 *
 * @author James Ide
 */
public class TorrentException extends Exception {

    private static final long serialVersionUID = 4379622768376828833L;

    /**
     * Constructs a {@code TorrentException} with {@code null} as its error
     * detail message.
     */
    public TorrentException() { }

    /**
     * Constructs a {@code TorrentException} with the specified detail message.
     *
     * @param message the detail message (which is saved for later retrieval
     *        by the {@link #getMessage()} method)
     */
    public TorrentException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code TorrentException} with the specified detail message
     * and cause.
     * <p>
     * Note that the detail message associated with {@code cause} is
     * <em>not</em> automatically incorporated into this exception's detail
     * message.
     *
     * @param message the detail message (which is saved for later retrieval
     *        by the {@link #getMessage()} method)
     * @param cause the cause (which is saved for later retrieval by the
     *        {@link #getCause()} method). (A null value is permitted, and
     *        indicates that the cause is nonexistent or unknown.)
     */
    public TorrentException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs an {@code TorrentException} with the specified cause and a
     * detail message of {@code (cause==null ? null : cause.toString())}
     * (which typically contains the class and detail message of {@code cause}).
     * This constructor is useful for IO exceptions that are little more
     * than wrappers for other throwables.
     *
     * @param cause the cause (which is saved for later retrieval by the
     *        {@link #getCause()} method). (A null value is permitted, and
     *        indicates that the cause is nonexistent or unknown.)
     */
    public TorrentException(String message, Throwable cause) {
        super(message, cause);
    }
}
