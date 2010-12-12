package edu.berkeley.poseidon.torrent;

import java.io.File;
import java.util.concurrent.Semaphore;

import org.apache.cassandra.db.Columns;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.thrift.Column;



public class Torrentizer {

	public Torrentizer() {
		try {
			uTorrentClient = Torrents.createUTorrentClient();
		} catch (TorrentException e) {
			e.printStackTrace();
			throw new RuntimeException("UTorrentClient didn't start");
		}
	}
	
	public static boolean isTorrent(Column col) {
	    return isTorrentColumn(Columns.fromThrift(col));
	}
	
    public static boolean columnNameIsTorrent(byte[] name) {
        return name.length >= 3 && name[0] == '_' && name[1] == '_' && name[2] == 'T';
    }

    public static boolean isTorrentColumn(IColumn c) {
        return columnNameIsTorrent(c.name());
    }

    public static boolean isTorrentWithData(IColumn c) {
        return isTorrentColumn(c) && !c.isMarkedForDelete();
    }

    public static boolean isDeletedTorrent(IColumn c) {
        return isTorrentColumn(c) && c.isMarkedForDelete();
    }

	/** True iff col.value is the pathName to a file. */
	public static boolean isPathName(Column col) {
		return col.name.length >= 5 && col.name[3] == '_' && col.name[4] == 'F';
	}
	
	/** Requires isTorrent(torrent.value) */
	public File fetchFile(Column torrent) {
		try {
			Listener listener = new Listener();
			uTorrentClient.download(decoder.decode(torrent.value), listener);
			listener.semaphore.acquireUninterruptibly();
			return listener.file;
			
		} catch (TorrentException e) {
			e.printStackTrace();
			throw new RuntimeException("Couldn't decode file we think is a torrent");
		}
	}
	
	
	public void seed(File file, Column torrent) {
		try {
			torrent.value = encoder.encode(uTorrentClient.seed(file));
		} catch (TorrentException e) {
			e.printStackTrace();
			throw new RuntimeException("We somehow couldn't seed a file");
		}
	}
	
	public String torrentDirectoryPathName() {
		return uTorrentClient.getActiveDirectory().getAbsolutePath();
	}
	
	public static class Listener implements TorrentListener {

		public File file;
		public Semaphore semaphore;
		
		public Listener() {
			semaphore = new Semaphore(1);
			semaphore.acquireUninterruptibly();
		}
		
		@Override
		public void fileDownloaded(Torrent torrent, File file) {
			this.file = file;
			semaphore.release();
		}

		@Override
		public void downloadFailed(Torrent torrent, TorrentException error) {
			error.printStackTrace();
			this.file = NULLNAMEFILE;
			semaphore.release();
			throw new RuntimeException("uTorrentClient fetch file failed");
		}
		
	}
	
	private UTorrentClient uTorrentClient;
	
	private final static TorrentDecoder decoder = new TorrentDecoder(new Bdecoder());
	private final static TorrentEncoder encoder = new TorrentEncoder(new Bencoder());
	
	private final static File NULLNAMEFILE = new File("null");
}
