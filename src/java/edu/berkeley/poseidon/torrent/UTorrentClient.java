package edu.berkeley.poseidon.torrent;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;

import org.apache.cassandra.utils.Pair;
import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.Files;
import com.google.common.io.NullOutputStream;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.representation.Form;
import com.sun.jersey.api.uri.UriComponent;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class UTorrentClient {

    private static Logger logger = Logger.getLogger(UTorrentClient.class);

    /**
     * The duration in milliseconds for which the CSRF token is valid.
     * uTorrent keeps the tokens valid for 30 minutes, so to be on the safe
     * side we refresh the token every 29 minutes.
     */
    private static final long CSRF_EXPIRATION_THRESHOLD = 29 * 60 * 1000;

    /* Indices of various values in a uTorrent response. */
    private static final int TORRENT_HASH_INDEX = 0;
    //private static final int TORRENT_STATUS_INDEX = 1;
    private static final int TORRENT_NAME_INDEX = 2;
    private static final int TORRENT_PROGRESS_INDEX = 4;

    /** The REST client used to make HTTP connections to uTorrent. */
    private final Client restClient;
    /** The HTTP request filter that appends the authorization credentials. */
    private final ClientFilter authFilter;
    /** The base URI of the uTorrent server's REST interface. */
    private final URI serverUri;
    /** The HTTP server, which is our mechanism for listening to uTorrent. */
    private final HttpServer httpServer;
    /** The completion handler, which is invoked when torrents finish. */
    private final TorrentCompletedHandler completedHandler;
    /** The directory where active downloads reside. */
    private final File activeDirectory;
    /** The directory where completed downloads reside. */
    private final File completedDirectory;

    /** The anti-CSRF token used by uTorrent. */
    private String csrfToken;
    /** The time in milliseconds when the CSRF token expires. */
    private long csrfTokenExpiration;

    private final TorrentEncoder encoder;
    /** The registry, which maps torrent names to callback listeners. */
    private final Multimap<String, Callback> callbackRegistry;

    /**
     * Creates a new client for interface with uTorrent. This constructor makes
     * blocking calls to the uTorrent server!
     *
     * @param client the REST client for communicating with uTorrent
     * @param uri the URI of the uTorrent server's REST interface
     * @param base the base directory where the uTorrent server resides
     * @param username the username to access the uTorrent server
     * @param password the password to access the uTorrent server
     * @param server the HTTP server for listening to "file completed" pings
     *        from uTorrent. The HTTP server is configured to listen to
     *        messages from uTorrent but it is not started. This is the
     * @throws TorrentException if an error occurs while reading the
     *         configuration details
     */
    public UTorrentClient(Client client, URI uri, File base,
                          String username, String password, HttpServer server)
                          throws TorrentException {
        restClient = client;
        authFilter = new HTTPBasicAuthFilter(username, password);
        serverUri = UriBuilder.fromUri(uri).replaceQuery(null).fragment(null)
                              .build();
        httpServer = server;
        encoder = new TorrentEncoder(new Bencoder());
        // Since HTTP request threads may access the registry, it is imperative
        // that access to it is synchronized.
        callbackRegistry = Multimaps.synchronizedMultimap(
            LinkedListMultimap.<String, Callback>create());

        // Read the uTorrent server settings.
        String activeDirectory = null;
        String completedDirectory = null;
        WebResource settingsResource = makeWebResource("action=getsettings");
        String response = settingsResource.get(String.class);
        logger.debug(response);
        try {
            JSONObject json = toJsonObject(response);
            List<?> settings = (JSONArray) json.get("settings");
            for (Object item : settings) {
                List<?> setting = (List<?>) item;
                if ("dir_active_download".equals(setting.get(0))) {
                    activeDirectory = (String) setting.get(2);
                } else if ("dir_completed_download".equals(setting.get(0))) {
                    completedDirectory = (String) setting.get(2);
                }
            }
        } catch (ClassCastException e) {
            throw new TorrentException(e);
        }

        if ((activeDirectory == null) || (completedDirectory == null)) {
            throw new TorrentException("could not find desired settings");
        }

        if (new File(activeDirectory).isAbsolute()) {
            this.activeDirectory = new File(activeDirectory);
        } else {
            this.activeDirectory =
                new File(base, activeDirectory).getAbsoluteFile();
        }

        if (completedDirectory.isEmpty()) {
            this.completedDirectory = this.activeDirectory;
        } else {
            if (new File(completedDirectory).isAbsolute()) {
                this.completedDirectory = new File(completedDirectory);
            } else {
                this.completedDirectory =
                    new File(base, completedDirectory).getAbsoluteFile();
            }
        }

        // Configure the HTTP server to listen to messages from uTorrent.
        completedHandler = new TorrentCompletedHandler();
        setUpHttpServer();
        logger.info("uTorrent Active directory is: " + this.activeDirectory);
        logger.info("uTorrent Completed directory is: " + this.completedDirectory);
    }

    private void setUpHttpServer() {
        httpServer.createContext("/download-finished", completedHandler);
        httpServer.start();
    }

    public void destroy() {
        httpServer.stop(0);
    }

    private WebResource makeWebResource(String query) throws TorrentException {
        ensureCsrfToken();
        URI uri = UriBuilder.fromUri(serverUri)
            .replaceQuery("token=" + csrfToken + "&" + query).build();
        WebResource resource = restClient.resource(uri);
        resource.addFilter(authFilter);
        return resource;
    }

    private void ensureCsrfToken() throws TorrentException {
        long now = System.currentTimeMillis();
        if ((csrfToken == null) || (now >= csrfTokenExpiration)) {
            URI tokenUri = UriBuilder.fromUri(serverUri).path("token.html")
                                     .build();
            WebResource tokenResource = restClient.resource(tokenUri);
            tokenResource.addFilter(authFilter);
            String html = tokenResource.get(String.class);

            Pattern tokenPattern =
                Pattern.compile("<[^>]+><[^>]+>([^<]+)</[^>]+></[^>]+>");
            Matcher matcher = tokenPattern.matcher(html);
            if (!matcher.find()) {
                throw new TorrentException("no CSRF token was found");
            }

            csrfToken = matcher.group(1);
            csrfTokenExpiration = now + CSRF_EXPIRATION_THRESHOLD;
        }
    }

    public static void main(String[] args) throws Exception {
        File torrentFile = new File("utorrent-server-v3_0/example.torrent");
        byte[] torrentBytes = Files.toByteArray(torrentFile);
        TorrentDecoder decoder = new TorrentDecoder(new Bdecoder());
        Torrent torrent = decoder.decode(torrentBytes);
        System.out.println(torrent);

        UTorrentClient client = Torrents.createUTorrentClient();
        System.out.println("Active directory :" + client.activeDirectory);
        System.out.println("Completed directory :" + client.completedDirectory);

        Torrent t = client.seed(new File("C:/Users/Ide/Desktop/rx.txt"));
        client.remove(t);

        client.download(t, new TorrentAdapter());
        client.remove(t);

        client.destroy();
    }

    public File getActiveDirectory() {
        return activeDirectory;
    }

    public File getCompletedDirectory() {
        return completedDirectory;
    }

    /**
     * Starts seeding the specified file and returns the newly created torrent
     * for that file. The file to seed must be in the active download directory
     * that is given by {@link #getActiveDirectory()}.
     * <p>
     * This method is blocking.
     *
     * @param file the file to start sharing
     * @throws TorrentException if an error occurs while creating the torrent
     *         or starting to seed
     * @throws IllegalArgumentException if the given file is not in uTorrent's
     *         active download directory
     * @throws NullPointerException if the given file is null
     */
    public Torrent seed(File file) throws TorrentException {
        checkNotNull(file);
        checkArgument(getActiveDirectory().equals(file.getParentFile()));

        // Create a new Torrent from the file. This takes time!
        Torrent torrent = new Torrent.PieceHasher()
            .addAnnounceUriGroup(ImmutableList.of(
                URI.create("http://50.18.56.165:80/announce"),
                URI.create("udp://50.18.56.165:80/announce")))
//            .addAnnounceUriGroup(ImmutableList.of(
//                URI.create("http://tracker.publicbt.com:80/announce"),
//                URI.create("udp://tracker.publicbt.com:80/announce")))
//            .addAnnounceUri(URI.create("udp://tracker.openbittorrent.com:80/announce"))
            .addFile(file)
            .build();
        addTorrent(torrent);

        return torrent;
    }

    public void download(Torrent torrent, TorrentListener listener)
            throws TorrentException {
        // Register the listener that is notified when a torrent completes.
        Callback callback = new Callback(torrent, listener);
        callbackRegistry.put(torrent.getName(), callback);

        if (isDownloaded(torrent)) {
            File file = getDownloadedFile(torrent);
            completedHandler.invokeCallbacks(torrent.getName(), file);
        } else if (!isDownloading(torrent)) {
            boolean added = false;
            try {
                addTorrent(torrent);
                added = true;
            } finally {
                if (!added) {
                    callbackRegistry.remove(torrent.getName(), callback);
                }
            }
        }
    }

    /**
     * Removes the specified torrent (stops downloading/seeding) and returns
     * true if such a torrent was found and false if otherwise.
     */
    public boolean remove(Torrent torrent) throws TorrentException {
        String response = makeWebResource("list=1").get(String.class);
        String name = torrent.getName();
        JSONObject json = toJsonObject(response);
        // Filter out all of the entries with the matching name.
        List<JSONArray> torrentEntries = torrentEntriesByName(name, json);

        if (!torrentEntries.isEmpty()) {
            // Extract their hashes (opaque IDs) from the entries.
            List<String> torrentHashes = Lists.newLinkedList();
            for (JSONArray entry : torrentEntries) {
                torrentHashes.add(entry.get(TORRENT_HASH_INDEX).toString());
            }

            // Create a set of URL parameters, specifying the hashes.
            MultivaluedMap<String, String> hashParameters = new Form();
            for (String hash : torrentHashes) {
                hashParameters.add("hash", hash);
            }
            WebResource removalResource = makeWebResource("action=remove")
                .queryParams(hashParameters);

            // Ensure there were no reported errors.
            toJsonObject(removalResource.get(String.class));
        }

        return !torrentEntries.isEmpty();
    }

    private boolean isDownloading(Torrent torrent) throws TorrentException {
        return getDownloadProgress(torrent) >= 0;
    }

    private boolean isDownloaded(Torrent torrent) throws TorrentException {
        // Progress is measured per mils, where 1000 represents 100%.
        return getDownloadProgress(torrent) == 1000;
    }

    private int getDownloadProgress(Torrent torrent) throws TorrentException {
        JSONObject json = toJsonObject(makeWebResource("list=1").get(String.class));
        List<JSONArray> entries = torrentEntriesByName(torrent.getName(), json);
        if (entries.isEmpty()) {
            return -1;
        }
        JSONArray entry = entries.get(0);
        return ((Number) entry.get(TORRENT_PROGRESS_INDEX)).intValue();
    }

    private File getDownloadedFile(Torrent torrent) throws TorrentException {
        WebResource resource = makeWebResource("list=1");
        JSONObject json = toJsonObject(resource.get(String.class));
        List<JSONArray> entries = torrentEntriesByName(torrent.getName(), json);
        if (entries.isEmpty()) {
            throw new TorrentException("no entry for torrent present");
        }

        JSONArray entry = entries.get(0);
        return new File(getCompletedDirectory(),
                        entry.get(TORRENT_NAME_INDEX).toString());

//        String hash = entry.get(TORRENT_HASH_INDEX).toString();
//        return getDownloadedFile(hash);
    }

    @SuppressWarnings("unused")
    private File getDownloadedFile(String hash) throws TorrentException {
        WebResource resource;
        try {
            resource = makeWebResource("action=getfiles&hash=" +
                                       URLEncoder.encode(hash, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new TorrentException(e);
        }

        JSONObject json = toJsonObject(resource.get(String.class));
        try {
            JSONArray fileEntries = (JSONArray) json.get("files");
            if (fileEntries.size() < 2) {
                throw new TorrentException("no file entry for torrent");
            }

            if (!hash.equals(fileEntries.get(0))) {
                String error = fileEntries.get(0) + " does not match hash";
                throw new TorrentException(error);
            }

            JSONArray entries = (JSONArray) fileEntries.get(1);
            JSONArray entry = (JSONArray) entries.get(0);
            return new File(getCompletedDirectory(), entry.get(0).toString());
        } catch (ClassCastException e) {
            throw new TorrentException(e);
        }
    }

    private void addTorrent(Torrent torrent) throws TorrentException {
        byte[] torrentBytes = encoder.encode(torrent);
        WebResource resource = makeWebResource("action=add-file");
        FormDataMultiPart multiPart = new FormDataMultiPart();
        multiPart.field("torrent_file", torrentBytes,
                        MediaType.APPLICATION_OCTET_STREAM_TYPE);
        String response = resource.type(MediaType.MULTIPART_FORM_DATA_TYPE)
                                  .post(String.class, multiPart);

        // Propagate any uTorrent error messages as a TorrentException.
        JSONObject json = toJsonObject(response);
 
        // Confirm that the torrent was added (if it was already present before
        // calling this method, uTorrent should have reported an error).
        json = toJsonObject(makeWebResource("list=1").get(String.class));
        List<JSONArray> entries = torrentEntriesByName(torrent.getName(), json);
        if (entries.isEmpty()) {
            throw new TorrentException(
                "uTorrent is not aware of added torrent");
        }
    }

    /**
     * Converts a raw JSON string into an actual JSON object, checking that
     * uTorrent did not specify an error message.
     */
    private static JSONObject toJsonObject(String response)
            throws TorrentException {
        try {
            Object rawObject = JSONValue.parseWithException(response);
            if (!(rawObject instanceof JSONObject)) {
                String type = rawObject.getClass().getName();
                throw new TorrentException("response is of JSON type " + type);
            }

            JSONObject json = (JSONObject) rawObject;
            // Propagate uTorrent errors as TorrentExceptions.
            if (json.containsKey("error")) {
                String error = String.valueOf(json.get("error"));
                throw new TorrentException(error);
            }
            return json;
        } catch (ParseException e) {
            throw new TorrentException(e);
        }
    }

    private static List<JSONArray> torrentEntriesByName(String name, JSONObject json)
            throws TorrentException {
        List<JSONArray> entries = Lists.newArrayList();
        if (json.get("torrents") instanceof JSONArray) {
            JSONArray allEntries = (JSONArray) json.get("torrents");
            for (Object item : allEntries) {
                if (item instanceof JSONArray) {
                    // If the name within the entry matches, we will keep it.
                    JSONArray entry = (JSONArray) item;
                    if (name.equals(entry.get(TORRENT_NAME_INDEX))) {
                        entries.add(entry);
                    }
                }
            }
        }
        return entries;
    }

    private class Callback extends Pair<Torrent, TorrentListener> {

        public Callback(Torrent torrent, TorrentListener listener) {
            super(torrent, listener);
        }

        public Torrent getTorrent() {
            return left;
        }

        public TorrentListener getListener() {
            return right;
        }
    }

    private class TorrentCompletedHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = streamContents(exchange.getRequestBody(), "UTF-8");
            MultivaluedMap<String, String> arguments =
                UriComponent.decodeQuery(query, true);
            PrintWriter out = new PrintWriter(exchange.getResponseBody());

            if (!arguments.containsKey("name") ||
                    !arguments.containsKey("file")) {
                exchange.sendResponseHeaders(400, 0);
                out.println("must specify torrent name and file");
            } else {
                exchange.sendResponseHeaders(200, 0);
                String name = arguments.getFirst("name");
                File file = new File(getCompletedDirectory(),
                                     arguments.getFirst("file"));
                invokeCallbacks(name, file, out);
            }
            out.flush();
            exchange.close();
        }

        private void invokeCallbacks(String name, File file) {
            try {
                invokeCallbacks(name, file,
                                new PrintWriter(new NullOutputStream()));
            } catch (IOException e) {
                logger.error("I/O error when invoking callbacks", e);
            }
        }
 
        private void invokeCallbacks(String name, File file,
                                     PrintWriter out) throws IOException {
            boolean successful = file.canRead();
            int called;
            int exceptions = 0;

            synchronized (callbackRegistry) {
                Collection<Callback> callbacks = callbackRegistry.get(name);
                called = callbacks.size();
                for (Callback callback : callbacks) {
                    Torrent torrent = callback.getTorrent();
                    TorrentListener listener = callback.getListener();
                    try {
                        if (successful) {
                            listener.fileDownloaded(torrent, file);
                        } else {
                            String error =
                                "file is not readable at " + file.getPath();
                            listener.downloadFailed(
                                torrent, new TorrentException(error));
                        }
                    } catch (Exception e) {
                        exceptions++;
                        logger.error("callback for torrent " + name + " failed",
                                     e);
                    }
                }
                callbackRegistry.removeAll(name);
            }

            out.println(called + " callbacks processed; " +
                        exceptions + " threw exceptions");
        }

        private String streamContents(InputStream in, String charset)
                throws IOException {
            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, charset));
            String line = reader.readLine();
            while (line != null) {
                builder.append(line);
                line = reader.readLine();
            }
            return builder.toString();
        }
    }
}
