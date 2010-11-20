package edu.berkeley.poseidon.torrent;

import java.io.File;

public class TorrentAdapter implements TorrentListener {

    @Override
    public void fileDownloaded(Torrent torrent, File file) { }

    @Override
    public void downloadFailed(Torrent torrent, TorrentException error) { }
}
