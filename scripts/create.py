#!/usr/bin/python
from __future__ import with_statement

import sys
import optparse
import os
from xml.dom import minidom

DEFAULT_PORT = 2020
DEFAULT_HOST = "0.0.0.0"
BT_PORT = 6881
CASS_PORT = 7000

class Port:

    # Really we need 5 but let's play it safe in case we need to add services.
    allocation = 10

    def __init__(self, port, host, torrentport=None):
        self.baseport = int(port)
        self.bindhost = host # e.g. 127.0.100.1, 2, etc.
        self.torrentport = torrentport or self.baseport + 2

    def cass_storage_port(self):
        return CASS_PORT # Must be constant between all nodes

    def cass_thrift_port(self):
        return self.baseport
    def jmx_port(self):
        return self.baseport + 1
    def ut_torrent_port(self):
        return self.torrentport
    def ut_http_port(self):
        return self.baseport + 3
    def cass_http_port(self):
        return self.baseport + 4

    def cass_listen_address(self):
        """ The ip address used to connect to services running on this machine """
        if self.bindhost == "0.0.0.0":
            return ""
        return self.bindhost

    def connect_address(self):
        """ The ip address used to connect to services running on this machine """
        if self.bindhost == "0.0.0.0":
            return "127.0.0.1"
        return self.bindhost

    def bind_address(self):
        """ The listening address used for services on this machine """
        return self.bindhost

def setupUTorrent(basedir, port):
    addLines = []
    with open(os.path.join("utorrent-server-v3_0","utconfig.txt"), "r") as readFile:
        lines = [l for l in readFile.xreadlines() if l.split(":", 1)[0] not in ("finish_cmd","bind_port","ut_webui_port","bind_ip")]
    with open(os.path.join(basedir,"utconfig.txt"), "w") as writeFile:
        writeFile.writelines(lines)
        writeFile.write("""
finish_cmd: "curl -o /dev/null http://%s:%d/finished?%%F"
bind_port: %d
ut_webui_port: %d
bind_ip: "%s"
""" % (port.connect_address(), port.cass_http_port(),
       port.ut_torrent_port(), port.ut_http_port(), port.bind_address()))

# http://www.onemanclapping.org/2010/03/running-multiple-cassandra-nodes-on.html
def setupCassandra(basedir, port, allNodes, isCli=False):
    def getXMLConfigElement(dom, key):
        return xml.getElementsByTagName(key)[0]
    def setXMLConfigValue(dom, key, value):
        element = getXMLConfigElement(dom, key)
        element.childNodes[0].nodeValue = str(value)
    def getXMLConfigValue(dom, key):
        element = getXMLConfigElement(dom, key)
        return element.childNodes[0].nodeValue

    try:
        os.mkdir(os.path.join(basedir, "conf"))
        os.mkdir(os.path.join(basedir, "active-data"))
    except OSError:
        pass

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
        setXMLConfigValue(xml, "ListenAddress", port.cass_listen_address())
        setXMLConfigValue(xml, "ThriftPort", port.cass_thrift_port())
        setXMLConfigValue(xml, "ThriftAddress", port.bind_address())

        setXMLConfigValue(xml, "TorrentListenPort", port.cass_http_port())
        setXMLConfigValue(xml, "TorrentListenAddress", port.bind_address())
        setXMLConfigValue(xml, "TorrentWebuiPort", port.ut_http_port())
        setXMLConfigValue(xml, "TorrentWebuiAddress", port.connect_address())

        setXMLConfigValue(xml, "SavedCachesDirectory", os.path.join(basedir, "active-data", "saved_caches"))
        setXMLConfigValue(xml, "CommitLogDirectory", os.path.join(basedir, "active-data", "commitlog"))
        setXMLConfigValue(xml, "DataFileDirectory", os.path.join(basedir, "active-data", "data"))

        seedsElem = getXMLConfigElement(xml, "Seeds")
        for ch in seedsElem.childNodes[:]:
            seedsElem.removeChild(ch)
        for n in allNodes:
            newEl = xml.createElement("Seed")
            newEl.appendChild(xml.createTextNode(n.connect_address()))
            seedsElem.appendChild(newEl)

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

    toplevel = os.getcwd()
    setupScript = """#!/bin/bash
cd %s
if [ -e utpid.txt ]; then
    UTPID=$(cat utpid.txt)
    kill "$UTPID"
    while $(ps -A | grep -q "$UTPID "); do
        sleep 0.1
    done
fi
if [ -e casspid.txt ]; then
    CASSPID=$(cat casspid.txt)
    kill "$CASSPID"
    while $(ps -A | grep -q "$CASSPID "); do
        sleep 0.1
    done
fi
%s/utorrent-server-v3_0/utserver -configfile utconfig.txt -daemon -pidfile utpid.txt
# Give utorrent time to start up.
until curl -o /dev/null http://%s:%d/ 2>/dev/null; do sleep 0.1; done
sleep 0.3
export CASSANDRA_INCLUDE=%s/node.in.sh
cd %s
""" % (basedir, toplevel, port.connect_address(), port.ut_http_port(), basedir, toplevel)

    if isCli:
        # Create a CLI script for this node.
        with open(os.path.join(basedir, "cli.sh"), "w") as writeCfg:
            writeCfg.write(setupScript)
            nodeString=" ".join("'--host %s --port %d'"%(host.connect_address(), host.cass_thrift_port()) for host in allNodes)
            writeCfg.write("""
function testnode () {
    echo -n | nc $2 $4
}
retval=1
for node in %s; do
    if testnode $node; then
        bin/cassandra-cli $node $* && retval=0
    fi
done

kill $(cat %s)

exit $retval
"""%(nodeString, os.path.join(basedir, "utpid.txt")))
        os.chmod(os.path.join(basedir, "cli.sh"), 0755)
    else:
        # Create a startup script for this node.
        with open(os.path.join(basedir, "startup.sh"), "w") as writeCfg:
            writeCfg.write(setupScript)
            writeCfg.write("\nbin/cassandra -p %s $*"%(os.path.join(basedir, "casspid.txt"), ))
        os.chmod(os.path.join(basedir, "startup.sh"), 0755)
        with open(os.path.join(basedir, "stop.sh"), "w") as writeCfg:
            writeCfg.write("#!/bin/bash\nkill $(cat %s)\nkill $(cat %s)"%(
                    os.path.join(basedir, "utpid.txt"),
                    os.path.join(basedir, "casspid.txt")))
        os.chmod(os.path.join(basedir, "stop.sh"), 0755)

def setupDirectory(basedir, port, allNodes, isCli=False):
    try:
        os.mkdir(basedir)
    except OSError:
        pass
    setupUTorrent(basedir, port)
    setupCassandra(basedir, port, allNodes, isCli)

if __name__ == '__main__':
    parser = optparse.OptionParser()
    parser.add_option("-c", "--cli", action="store_true", dest="isCli", default=False, help="Set if this is a cli instance.")
    parser.add_option("--btport", dest="btport", default=BT_PORT, help="Set the port number for bittorrent (%d or 0)"%(BT_PORT,))
    parser.add_option("-n", "--nodes", dest="allNodes", help="Comma-separated list of ip-address:port pairs of all non-CLI nodes. Must include self.")
    parser.add_option("-d", "--dir", "--db", dest="dir", help="Base directory")
    parser.add_option("-p", "--port", dest="port", help="First of %d consecutive ports."%Port.allocation, default=DEFAULT_PORT)
    parser.add_option("-l", "--listen", dest="host", help="Host to listen (default 0.0.0.0)", default=DEFAULT_HOST)
    (options, args) = parser.parse_args()

    dir = options.dir
    allNodes = []
    print "isCli: %s"%(repr(options.isCli),)
    if not options.allNodes:
        print >>sys.stderr, "Must specify a list of nodes"
        parser.print_help()
        sys.exit(1)

    for ipPort in options.allNodes.split(","):
        hostport = ipPort.split(":")
        host = hostport[0]
        port = DEFAULT_PORT
        if len(hostport) > 1:
            port = hostport[1]
        allNodes.append(Port(port, host))

    if not dir or dir[0] == ".":
        print >>sys.stderr, "Cannot use current directory for configuration"
        parser.print_help()
        sys.exit(1)

    setupDirectory(basedir=dir, port=Port(int(options.port), options.host, int(options.btport)), allNodes=allNodes, isCli=options.isCli)
    print "Finished setting up directory %s"%(dir,)
