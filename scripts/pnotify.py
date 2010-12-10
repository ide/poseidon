#!/usr/bin/env python
import sys
from urllib import urlencode
from urllib2 import urlopen

def notify_poseidon(torrent_name, torrent_file):
    """Notifies a locally running Poseidon server that the torrent with the
    given name has been downloaded to the specified file.
    """
    url = 'http://127.0.0.1:8081/download-finished'
    query = urlencode({'name': torrent_name, 'file': torrent_file})
    connection = urlopen(url, query)
    print connection.read()

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print 'Usage: python pnotify.py <name> <file>'
        sys.exit(2)
    torrent_name, torrent_file = sys.argv[1:3]
    notify_poseidon(torrent_name, torrent_file)
