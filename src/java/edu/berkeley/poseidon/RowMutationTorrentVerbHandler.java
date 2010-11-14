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
import org.apache.cassandra.db.RowMutationVerbHandler;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.StorageService.Verb;
import org.apache.cassandra.utils.WrappedRunnable;
import org.apache.log4j.Logger;


public class RowMutationTorrentVerbHandler implements IVerbHandler {

    private class TorrentCompleted implements TorrentManager.SeedListener {
        private Set<File> torrentFiles;
        private Message mutation;

        public TorrentCompleted (RowMutation mutation, Set<File> torrentFiles) throws IOException {
            this.torrentFiles = torrentFiles;
            this.mutation = mutation.makeRowMutationMessage();
        }

        public void finishedDownload(File torrentFile) {
            torrentFiles.remove(torrentFile);
            if (torrentFiles.size() == 0) {
                finishedAll();
            }
        }
        
        public void finishedAll() {
            Runnable runnable = new WrappedRunnable()
            {
                public void runMayThrow() throws IOException
                {
                    MessagingService.instance.getVerbHandler(Verb.MUTATION).doVerb(mutation);
                }
            };
            StageManager.getStage(StageManager.MUTATION_STAGE).execute(runnable);
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
            TorrentCompleted status = new TorrentCompleted(rm, torrentFilesToProcess);
            boolean waitingForTorrents = false;
            for (File torrentFile : torrentFilesToProcess) {
                waitingForTorrents = true;
                manager.addTorrentFile(torrentFile, status);
            }
            if (!waitingForTorrents) {
                status.finishedAll();
            }
        }
        catch (IOException e)
        {
            logger_.error("Error in row mutation", e);
        }
	}

}
