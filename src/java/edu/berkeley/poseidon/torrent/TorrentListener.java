package edu.berkeley.poseidon.torrent;

import java.io.File;

public interface TorrentListener {

    void fileDownloaded(Torrent torrent, File file);

    void downloadFailed(Torrent torrent, Exception error);
}
