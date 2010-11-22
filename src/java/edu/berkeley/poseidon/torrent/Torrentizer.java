package edu.berkeley.poseidon.torrent;

import java.io.File;

import org.apache.cassandra.thrift.Column;

/** 
 * No need to notify caller, though the class has to be thread safe.
 * 
 * @author mli
 *
 */
public interface Torrentizer {
	
	boolean isTorrent(Column col);
	
	File fetchFile(Column torrent);
	
	void seed(File file, Column torrent);
	
	String torrentDirectoryPathName();
	
}
