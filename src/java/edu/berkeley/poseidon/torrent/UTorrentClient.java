package edu.berkeley.poseidon.torrent;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.representation.Form;
import com.sun.jersey.multipart.FormDataMultiPart;

public class UTorrentClient {

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

    /** The REST client used to make HTTP connections to uTorrent. */
    private final Client restClient;
    /** The HTTP request filter that appends the authorization credentials. */
    private final ClientFilter authFilter;
    /** The base URI of the uTorrent server's REST interface. */
    private final URI serverUri;
    /** The directory where active downloads reside. */
    private final File activeDirectory;
    /** The directory where completed downloads reside. */
    private final File completedDirectory;

    /** The anti-CSRF token used by uTorrent. */
    private String csrfToken;
    /** The time in milliseconds when the CSRF token expires. */
    private long csrfTokenExpiration;

    private final TorrentEncoder encoder;

    /**
     * Creates a new client for interface with uTorrent. This constructor makes
     * blocking calls to the uTorrent server!
     *
     * @param client the REST client for communicating with uTorrent
     * @param uri the URI of the uTorrent server's REST interface
     * @param base the base directory where the uTorrent server resides
     * @param username the username to access the uTorrent server
     * @param password the password to access the uTorrent server
     * @throws TorrentException if an error occurs while reading the
     *         configuration details
     */
    public UTorrentClient(Client client, URI uri, File base,
                          String username, String password)
                          throws TorrentException {
        restClient = client;
        authFilter = new HTTPBasicAuthFilter(username, password);
        serverUri = UriBuilder.fromUri(uri).replaceQuery(null).fragment(null)
                              .build();

        // Read the uTorrent server settings.
        String activeDirectory = null;
        String completedDirectory = null;
        WebResource settingsResource = makeWebResource("action=getsettings");
        String response = settingsResource.get(String.class);
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

        encoder = new TorrentEncoder(new Bencoder());
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
        File torrentFile = new File("C:/Users/Ide/Desktop/test.torrent");
        byte[] torrentBytes = Files.toByteArray(torrentFile);
        TorrentDecoder decoder = new TorrentDecoder(new Bdecoder());
        Torrent torrent = decoder.decode(torrentBytes);
        System.out.println(torrent);

        UTorrentClient client = new UTorrentClient(Client.create(),
            URI.create("http://localhost:8080/gui/"),
            new File("C:/Users/Ide/Desktop"), "admin", "");
        System.out.println("Active directory :" + client.activeDirectory);
        System.out.println("Completed directory :" + client.completedDirectory);
        Torrent t = client.seed(new File("C:/Users/Ide/Desktop/Effective Java 2nd Edition.pdf"));
        client.remove(t);

        client.download(t, new TorrentAdapter());
        client.remove(t);
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
            .addAnnounceUri(URI.create("udp://tracker.publicbt.com:80/announce"))
            .addAnnounceUri(URI.create("udp://tracker.openbittorrent.com:80/announce"))
            .addFile(file)
            .build();
        addTorrent(torrent);
        return torrent;
    }

    public void download(Torrent torrent, TorrentListener listener)
            throws TorrentException {
        addTorrent(torrent);

        // TODO: Register the event handler for when the torrent is complete.
        // This entails setting up a little HTTP server or something.
        // Then we should run this.
        File downloaded = new File(this.getCompletedDirectory(),
                                   torrent.getName());
        if (downloaded.canRead()) {
            listener.fileDownloaded(torrent, downloaded);
        } else {
            String error = "file is not readable at " + downloaded.getPath();
            listener.downloadFailed(torrent, new TorrentException(error));
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
    private JSONObject toJsonObject(String response)
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

    private List<JSONArray> torrentEntriesByName(String name, JSONObject json)
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
}
