package edu.berkeley.poseidon;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class TorrentManager {
    
    public interface SeedListener {
        public void finishedDownload(File torrentFile);
    }

    File directory;
    
    private byte[] getHash(byte[] torrentContents) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return sha1.digest(torrentContents);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
    
    static final char[]DIGITS = "0123456789ABCDEF".toCharArray();
    private String getTorrentFilename(byte[] torrentContents) {
        byte[] hash = getHash(torrentContents);
        StringBuilder out = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; i++) {
            out.append(DIGITS[hash[i] >>> 4]);
            out.append(DIGITS[hash[i] & 15]);
        }
        out.append(".torrent");
        return out.toString();
    }
    private File getTorrentFilepath(byte[] torrentContents) {
        return new File(directory.toString() + File.separatorChar +
                getTorrentFilename(torrentContents));
    }
    
    public File writeTorrentFile(byte[] torrentContents) throws IOException {
        File ret = getTorrentFilepath(torrentContents);
        if (!ret.exists()) {
            OutputStream os = new FileOutputStream(ret);
            os.write(torrentContents);
            os.close();
        }
        return ret;
    }

    public void addTorrentFile(File filename, SeedListener callWhenFinished) {
        // TODO: Add REST call here.
        System.out.println("Adding torrent file " + filename);
        // HACK: Just see what happens for now.
        callWhenFinished.finishedDownload(filename);
    }
}
