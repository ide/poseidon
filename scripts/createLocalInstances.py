#!/usr/bin/python
from __future__ import with_statement

import create
import sys
import os

def createInst(basedir, clusterid, i):
	create.CreateInst(dir, port)
	return port, dir

def createInstances(basedir, clusterid, num):
	os.mkdir(basedir)
	ports = [create.Port(
		2000 + 1000 * clusterid + 10*i,
		"127.%d.%d.1"%(clusterid, i))
		for i in range(num)]
	dirs = ["%s/%d"%(basedir, i) for i in range(num)]
	allNodes = [p.connect_address() for p in ports]
	for thisdir, thisport in zip(dirs, ports):
		create.setupDirectory(thisdir, thisport, allNodes)
	startAllScript = "%s/startall.sh"%(basedir, )
	with open(startAllScript, 'w') as script:
		script.write("""
#!/bin/bash
for dir in {0..%d}; do
	%s/$dir/startup.sh
done
"""%(num, basedir))
	os.chmod(startAllScript, 0755)

if __name__=="__main__"
