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

import edu.berkeley.poseidon.torrent.Bdecoder;
import edu.berkeley.poseidon.torrent.Torrent;
import edu.berkeley.poseidon.torrent.TorrentDecoder;
import edu.berkeley.poseidon.torrent.TorrentException;
import edu.berkeley.poseidon.torrent.TorrentListener;
import edu.berkeley.poseidon.torrent.UTorrentClient;


public class RowMutationTorrentVerbHandler implements IVerbHandler {

    private static Logger logger_ = Logger.getLogger(RowMutationTorrentVerbHandler.class);

    private class TorrentCompleted implements TorrentListener {
        private Set<Torrent> torrentFiles;
        private Message mutation;

        public TorrentCompleted (RowMutation mutation, Set<Torrent> torrentFiles) throws IOException {
            this.torrentFiles = torrentFiles;
            this.mutation = mutation.makeRowMutationMessage();
        }

        public synchronized void fileDownloaded(Torrent torrent, File torrentFile) {
            torrentFiles.remove(torrent);
            if (torrentFiles.size() == 0) {
                finishedAll();
            }
        }

        public synchronized void downloadFailed(Torrent torrent, TorrentException error) {
            logger_.error("Torrent download failed: \n"+torrent.toString(), error);
            fileDownloaded(torrent, null);
        }

        public synchronized void finishedAll() {
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

    private UTorrentClient client_;
    
    public RowMutationTorrentVerbHandler(UTorrentClient client) {
        this.client_ = client;
    }
    
	public void doVerb(Message message) {
        byte[] bytes = message.getMessageBody();
        ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);
        try
        {
            RowMutation rm = RowMutation.serializer().deserialize(new DataInputStream(buffer));
            if (logger_.isDebugEnabled())
              logger_.debug("Applying " + rm);
            Set<Torrent> torrentFilesToProcess = new HashSet<Torrent>();
            TorrentDecoder decoder = new TorrentDecoder(new Bdecoder());
            for (ColumnFamily cf : rm.getColumnFamilies()) {
                for (IColumn cm : cf.getColumnsMap().values()) {
                    if (Column.isTorrent(cm)) {
                        try {
                            byte[] torrentContents = cm.value();
                            torrentFilesToProcess.add(decoder.decode(torrentContents));
                        } catch (TorrentException e) {
                            logger_.error("Malformed torrent in column "+cm+".", e);
                        }
                    }
                }
            }
            TorrentCompleted status = new TorrentCompleted(rm, torrentFilesToProcess);
            boolean waitingForTorrents = false;
            for (Torrent torrentFile : torrentFilesToProcess) {
                try {
                    waitingForTorrents = true;
                    client_.download(torrentFile, status);
                } catch (TorrentException e) {
                    logger_.error("Failed to add torrent:\n"+torrentFile, e);
                    status.downloadFailed(torrentFile, e);
                }
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
