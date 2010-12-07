package edu.berkeley.poseidon.torrent;

import java.io.File;
import java.util.concurrent.Semaphore;

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
		try {
			//FIXME: to check col.name and have col.name define the type of col?
			decoder.decode(col.value);
			return true;
		} catch (TorrentException e) {
			return false;
		}
	}
	
	/** True iff col.value is the pathName to a file in torrentDirectory. */
	public static boolean isPathName(Column col) {
		//FIXME: to check col.name implies input col.value is always a filename
		return true;
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
			//FIXME maybe throw NotFoundException?
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
