package edu.berkeley.poseidon.torrent;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.UriBuilder;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import com.google.common.io.Files;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

public class UTorrentClient {

    /**
     * The duration in milliseconds for which the CSRF token is valid.
     * uTorrent keeps the tokens valid for 30 minutes, so to be on the safe
     * side we refresh the token every 29 minutes.
     */
    private static final long CSRF_EXPIRATION_THRESHOLD = 29 * 60 * 1000;

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

    /**
     * Creates a new client for interface with uTorrent. This constructor makes
     * blocking calls to the uTorrent server!
     *
     * @param client the REST client for communicating with uTorrent
     * @param uri the URI of the uTorrent server's REST interface
     * @param username the username to access the uTorrent server
     * @param password the password to access the uTorrent server
     * @throws TorrentException if an error occurs while reading the
     *         configuration details
     */
    public UTorrentClient(Client client, URI uri, String username,
                          String password)
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
            JSONObject json =
                (JSONObject) JSONValue.parseWithException(response);
            List<?> settings = (JSONArray) json.get("settings");
            for (Object item : settings) {
                List<?> setting = (List<?>) item;
                if ("dir_active_download".equals(setting.get(0))) {
                    activeDirectory = (String) setting.get(2);
                } else if ("dir_completed_download".equals(setting.get(0))) {
                    completedDirectory = (String) setting.get(2);
                }
            }
        } catch (ParseException e) {
            throw new TorrentException(e);
        } catch (ClassCastException e) {
            throw new TorrentException(e);
        }

        if ((activeDirectory == null) || (completedDirectory == null)) {
            throw new TorrentException("could not find desired settings");
        }
        this.activeDirectory = new File(activeDirectory);
        this.completedDirectory = new File(completedDirectory);
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
            URI.create("http://localhost:8080/gui/"), "admin", "");
        System.out.println(client.activeDirectory);
        System.out.println(client.completedDirectory);
    }

    public void seed() {
        
    }

    public void download(Torrent torrent) {
        
    }
    
    public void remove(Torrent torrent) {
        
    }
}
