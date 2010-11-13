package edu.berkeley.poseidon;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.service.StorageService;
import org.apache.log4j.Logger;


public class RowMutationTorrentVerbHandler implements IVerbHandler {

    private class TorrentCompleted implements TorrentManager.SeedListener {
        private Set<File> torrentFiles;
        private Message mutation;

        public TorrentCompleted (Message mutation, Set<File> torrentFiles) {
            this.torrentFiles = torrentFiles;
            this.mutation = mutation;
        }

        public void finishedDownload(File torrentFile) {
            torrentFiles.remove(torrentFile);
            if (torrentFiles.size() == 0) {
                // TODO: Figure out how to properly run a mutation!
                // There's rm.apply(), which is sometimes called from StorageProxy, but we want to run
                // on a different SEDA stage I think.
                //StageManager.getStage(StageManager.MUTATION_STAGE).runThisMutation(mutation);
            }
        }
    }
    
    private static Logger logger_ = Logger.getLogger(RowMutationTorrentVerbHandler.class);

    private TorrentManager manager;
    
    public RowMutationTorrentVerbHandler(TorrentManager mgr) {
    	manager = mgr;
    }
    
	public void doVerb(Message message) {
        byte[] bytes = message.getMessageBody();
        ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);
        try
        {
            RowMutation rm = RowMutation.serializer().deserialize(new DataInputStream(buffer));
            if (logger_.isDebugEnabled())
              logger_.debug("Applying " + rm);
            Set<File> torrentFilesToProcess = new HashSet<File>();
            for (ColumnFamily cf : rm.getColumnFamilies()) {
                for (IColumn cm : cf.getColumnsMap().values()) {
                    if (Column.isTorrent(cm)) {
                        byte[] torrentContents = cm.value();
                        torrentFilesToProcess.add(manager.writeTorrentFile(torrentContents));
                    }
                }
            }
            Message mutationMessage = rm.makeRowMutationMessage(StorageService.Verb.MUTATION);
            TorrentCompleted status = new TorrentCompleted(mutationMessage, torrentFilesToProcess);
            for (File torrentFile : torrentFilesToProcess) {
                manager.addTorrentFile(torrentFile, status);
            }
        }
        catch (IOException e)
        {
            logger_.error("Error in row mutation", e);
        }
	}

}
