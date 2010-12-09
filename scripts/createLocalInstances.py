#!/usr/bin/python
from __future__ import with_statement

import create
import sys
import os
import optparse

def createInst(basedir, clusterid, i):
	create.CreateInst(dir, port)
	return port, dir

def createInstances(basedir, clusterid, num):
        try:
                os.mkdir(basedir)
        except OSError:
                pass
	ports = [create.Port(
		2000 + 1000 * clusterid + 10*i,
		"127.%d.%d.1"%(clusterid, i))
		for i in range(1, num + 1)]
	dirs = ["%s/%d"%(basedir, i) for i in range(num)]
	allNodes = [p for p in ports]
	for thisdir, thisport in zip(dirs, ports):
		create.setupDirectory(thisdir, thisport, allNodes)

        create.setupDirectory("%s/cli"%(basedir,),
                              create.Port(2000 + 1000 * clusterid, "127.%d.%d.1"%(clusterid, 0)),
                              allNodes,
                              isCli=True)

	restartAllScript = "%s/restartall.sh"%(basedir, )
	with open(restartAllScript, 'w') as script:
		script.write("""#!/bin/bash -x
for dir in {0..%d}; do
	%s/$dir/restart.sh
done
"""%(num-1, basedir))
	os.chmod(restartAllScript, 0755)
	startAllScript = "%s/startall.sh"%(basedir, )
	with open(startAllScript, 'w') as script:
		script.write("""#!/bin/bash -x
for dir in {0..%d}; do
	%s/$dir/startup.sh
done
"""%(num-1, basedir))
	os.chmod(startAllScript, 0755)
	stopAllScript = "%s/stopall.sh"%(basedir, )
	with open(stopAllScript, 'w') as script:
		script.write("""#!/bin/bash -x
for dir in {0..%d}; do
	%s/$dir/stop.sh
done
"""%(num-1, basedir))
	os.chmod(stopAllScript, 0755)

if __name__=="__main__":
    parser = optparse.OptionParser()
    parser.add_option("-d", "--dir", dest="dir", help="Top-level directory for all nodes")
    parser.add_option("-n", "--num", dest="numNodes", help="How many nodes to make.")
    parser.add_option("-c", "--clusterid", default=0, dest="clusterid", help="Cluster ID (default 0).")
    (options, args) = parser.parse_args()

    if not options.dir or not options.numNodes:
        print >>sys.stderr, "Must specify number of nodes (-n) and directory (-d)"
        parser.print_help()
        sys.exit(1)

    createInstances(options.dir, options.clusterid, int(options.numNodes))
