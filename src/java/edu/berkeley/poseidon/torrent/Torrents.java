package edu.berkeley.poseidon.torrent;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;

import com.sun.jersey.api.client.Client;
import com.sun.net.httpserver.HttpServer;

/**
 * A collection of helper functions that operate on {@link Torrent} objects.
 *
 * @author James Ide
 */
public final class Torrents {

    private static final File UTORRENT_BASE_DIRECTORY = new File("");
    private static final URI UTORRENT_SERVER_URI =
        URI.create("http://127.0.0.1:8080/gui/");
    private static final String UTORRENT_USERNAME = "admin";
    private static final String UTORRENT_PASSWORD = "";
    private static final InetSocketAddress HTTP_LOCALHOST =
        new InetSocketAddress("127.0.0.1", 8081);

    private Torrents() { }

    public static UTorrentClient createUTorrentClient()
            throws TorrentException {
        HttpServer httpServer;
        try {
            httpServer = HttpServer.create(HTTP_LOCALHOST, 0);
        } catch (IOException e) {
            throw new TorrentException(e);
        }
        return new UTorrentClient(new Client(),
                                  UTORRENT_SERVER_URI,
                                  UTORRENT_BASE_DIRECTORY,
                                  UTORRENT_USERNAME,
                                  UTORRENT_PASSWORD,
                                  httpServer);
    }
}
