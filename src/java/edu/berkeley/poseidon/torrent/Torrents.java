package edu.berkeley.poseidon.torrent;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.cassandra.config.DatabaseDescriptor;

import com.sun.jersey.client.apache.ApacheHttpClient;
import com.sun.jersey.client.apache.config.ApacheHttpClientConfig;
import com.sun.jersey.client.apache.config.DefaultApacheHttpClientConfig;
import com.sun.net.httpserver.HttpServer;


/**
 * A collection of helper functions that operate on {@link Torrent} objects.
 *
 * @author James Ide
 */
public final class Torrents {

    private static final File UTORRENT_BASE_DIRECTORY = new File("");
    private static final URI UTORRENT_SERVER_URI;
    private static final String UTORRENT_USERNAME = "admin";
    private static final String UTORRENT_PASSWORD = "";
    private static final InetSocketAddress HTTP_LOCALHOST =
        new InetSocketAddress(DatabaseDescriptor.getTorrentListenAddress(),
                              DatabaseDescriptor.getTorrentListenPort());

    static {
        String host = DatabaseDescriptor.getTorrentWebuiAddress().getHostName();
        int port = DatabaseDescriptor.getTorrentWebuiPort();
        try {
            UTORRENT_SERVER_URI = new URI("http", null, host, port, "/gui/",
                                          null, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private Torrents() { }

    public static UTorrentClient createUTorrentClient()
            throws TorrentException {
        HttpServer httpServer;
        try {
            httpServer = HttpServer.create(HTTP_LOCALHOST, 0);
        } catch (IOException e) {
            throw new TorrentException(e);
        }

        ApacheHttpClientConfig clientConfig =
                new DefaultApacheHttpClientConfig();
        clientConfig.getProperties()
            .put(ApacheHttpClientConfig.PROPERTY_HANDLE_COOKIES, true);

//        String host = DatabaseDescriptor.getTorrentWebuiAddress().getHostName();
//        int port = DatabaseDescriptor.getTorrentWebuiPort();
//        clientConfig.getState().setCredentials(null, host, port,
//                                               UTORRENT_USERNAME,
//                                               UTORRENT_PASSWORD);

        return new UTorrentClient(ApacheHttpClient.create(clientConfig),
                                  UTORRENT_SERVER_URI,
                                  UTORRENT_BASE_DIRECTORY,
                                  UTORRENT_USERNAME,
                                  UTORRENT_PASSWORD,
                                  httpServer);
    }
}
