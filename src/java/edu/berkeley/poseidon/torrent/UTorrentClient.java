package edu.berkeley.poseidon.torrent;

import com.sun.jersey.api.client.Client;

public class UTorrentClient {

    private final Client restClient;

    public UTorrentClient(Client client) {
        restClient = client;
    }

    
}
