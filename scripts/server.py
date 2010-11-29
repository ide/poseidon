#!/usr/bin/python
from __future__ import with_statement

import sys
import optparse
import os
from xml.dom import minidom

class Port:

    # Really we need 5 but let's play it safe in case we need to add services.
    allocation = 10

    def __init__(self, port, host):
        self.baseport = port
        self.bindhost = host # e.g. 127.0.100.1, 2, etc.

    def cass_storage_port(self):
        return 7000 # Must be constant between all nodes

    def cass_thrift_port(self):
        return self.baseport
    def jmx_port(self):
        return self.baseport + 1
    def ut_torrent_port(self):
        return self.baseport + 2
    def ut_http_port(self):
        return self.baseport + 3
    def cass_http_port(self):
        return self.baseport + 4

    def connect_address(self):
        """ The ip address used to connect to services running on this machine """
        if self.bindhost == "0.0.0.0":
            return "127.0.0.1"
        return self.bindhost

    def bind_address(self):
        """ The listening address used for services on this machine """
        return self.bindhost

def setupUTorrent(basedir, port):
    f = open(os.path.join(basedir, "utconfig.txt"), "wt")
    f.write("""
bind_port: %d
ut_webui_port: %d
bind_ip: "%s"
finish_cmd: "curl -o /dev/null http://%s:%d/finished?%%F"
dir_active: "ut_active"
dir_completed: "ut_completed"
dir_download: "ut_download"
dir_torrent_files: "ut_torrentfiles"
dir_temp_files: "ut_temp"
upnp: 0
natpmp: 0
lsd: 0
dht: 0
pex: 0
""" % (port.ut_torrent_port(), port.ut_http_port(), port.bind_address(),
       port.connect_address(), port.cass_http_port()));
    f.close()

# http://www.onemanclapping.org/2010/03/running-multiple-cassandra-nodes-on.html
def setupCassandra(basedir, port):
    def getXMLConfigElement(dom, key):
        return xml.getElementsByTagName(key)[0]
    def setXMLConfigValue(dom, key, value):
        element = getXMLConfigElement(dom, key)
        element.childNodes[0].nodeValue = str(value)
    def getXMLConfigValue(dom, key):
        element = getXMLConfigElement(dom, key)
        return element.childNodes[0].nodeValue

    os.mkdir(os.path.join(basedir, "conf"))

    # Copy all configuration files
    for f in os.listdir("conf"):
        with open(os.path.join("conf", f)) as readCfg:
            contents = readCfg.read()
            with open(os.path.join(basedir, "conf", f), "w") as writeCfg:
                writeCfg.write(contents)

    # Modify addresses and ports in storage-conf.xml.
    with open(os.path.join("conf", "storage-conf.xml")) as readCfg:
        xml = minidom.parse(readCfg)
        setXMLConfigValue(xml, "StoragePort", port.cass_storage_port())
        setXMLConfigValue(xml, "ListenAddress", port.bind_address())
        setXMLConfigValue(xml, "ThriftPort", port.cass_thrift_port())
        setXMLConfigValue(xml, "ThriftAddress", port.bind_address())

        setXMLConfigValue(xml, "TorrentListenPort", port.cass_http_port())
        setXMLConfigValue(xml, "TorrentListenAddress", port.bind_address())
        setXMLConfigValue(xml, "TorrentWebuiPort", port.ut_http_port())
        setXMLConfigValue(xml, "TorrentWebuiAddress", port.connect_address())

        with open(os.path.join(basedir, "conf", "storage-conf.xml"), "w") as writeCfg:
            writeCfg.write(xml.toxml())

    # Modify the startup script to have our new host/port
    with open(os.path.join("bin", "cassandra.in.sh")) as readCfg:
        lines = readCfg.readlines()
        for i in range(len(lines)):
            if "cassandra_home=" in lines[i]:
                lines[i] = "cassandra_home=" + os.getcwd() + "\n"
            elif "CASSANDRA_CONF=" in lines[i]:
                lines[i] = "CASSANDRA_CONF=" + basedir + "/conf"
            elif "-Dcom.sun.management.jmxremote.port=" in lines[i]:
                lines[i] = "-Dcom.sun.management.jmxremote.port=%d\n" % port.jmx_port() + \
                    "-Xrunjdwp:transport=dt_socket,server=y,address="+port.bind_address()+":8888,suspend=n\n"
        with open(os.path.join(basedir, "node.in.sh"), "w") as writeCfg:
            writeCfg.writelines(lines)

    # Create a startup script for this node.
    with open(os.path.join(basedir, "startup.sh"), "w") as writeCfg:
        writeCfg.write("""#!/bin/sh
export CASSANDRA_INCLUDE=%s/node.in.sh
cd %s
if [ -e utpid.txt]; then
    UTPID=$(cat utpid.txt)
    kill $UTPID
    while $(ps -A | grep -q $UTPID); do
        sleep 0.1
    done
fi
/opt/bittorrent-server-v3_0/utserver -configfile utconfig.txt -daemon -pidfile utpid.txt
# Give utorrent time to start up.
until curl -o /dev/null http://%s:%d/; do sleep 0.1; done
sleep 0.3
bin/cassandra $*
""" % (basedir, os.getcwd(), port.connect_address(), port.ut_http_port()))
    os.chmod(os.path.join(basedir, "startup.sh"), 0755)

def setupDirectory(basedir, port):
    os.mkdir(basedir)
    setupUTorrent(basedir, port)
    setupCassandra(basedir, port)

if __name__ == '__main__':
    parser = optparse.OptionParser()
    parser.add_option("-d", "--dir", "--db", dest="dir", help="Base directory")
    parser.add_option("-p", "--port", dest="port", help="First of %d consecutive ports."%Port.allocation, default=2020)
    parser.add_option("-l", "--listen", dest="host", help="Host to listen (default 0.0.0.0)", default="0.0.0.0")
    (options, args) = parser.parse_args()

    if not options.dir or options.dir[0] == ".":
        print >>sys.stderr, "Cannot use current directory for configuration"
        parser.print_help()
        sys.exit(1)

    setupDirectory(basedir=options.dir, port=Port(options.port, options.host))
