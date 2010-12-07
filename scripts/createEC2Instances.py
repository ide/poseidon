
#!/usr/bin/python
from __future__ import with_statement

import create
import sys
import os
import optparse
from subprocess import Popen, PIPE

def getInstanceInfo(cluster=None):
           pipe = Popen("ec2-describe-instances", stdout=PIPE)
    output = pipe.communicate()[0]
    """RESERVATION	r-bb214bd1	051423782292	Cassandra
INSTANCE	i-d8c79db5	ami-2272864b	ec2-67-202-15-186.compute-1.amazonaws.com	ip-10-242-49-18.ec2.internal	running	patrick	0		t1.micro	2010-12-06T23:34:32+0000	us-east-1a	aki-427d952b		monitoring-disabled	67.202.15.186	10.242.49.18			ebs	paravirtual	
BLOCKDEVICE	/dev/sda1	vol-fb9e5c93	2010-12-06T23:34:44.000Z	
TAG	instance	i-d8c79db5	Cluster	test
TAG	instance	i-d8c79db5	Name	Test
TAG	instance	i-d8c79db5	Node	0
"""
    instances = {}
    for line in output.split("\n"):
        if not line:
            continue
        info = line.split()
        if info[0]=="INSTANCE":
            id = info[1]
            if id in instances:
                print output
                raise ValueError("Duplicate Instance: %s"%(id,))
            datacenter = info[10]
            state = info[5]
            extip = info[3] #info[13]
            intip = info[4] #info[14]
            user = "ec2-user"
            instances[id] = {"tag": {}, "extip": extip, "intip": intip, "datacenter": datacenter, "state": state, "username": user}
        if info[0]=="TAG":
            id = info[2]
            key = info[3]
            value = info[4]
            instances[id]["tag"][key] = value
    return dict((k,v) for (k,v) in instances.iteritems() if not cluster or v["tag"]["Cluster"]==cluster)

def createInstances(basedir, instInfo, certfile):
    try:
        os.mkdir(basedir)
    except OSError:
        pass
    sshHosts = ["%s@%s"%(noe["user"],node["extip"]) for node in instInfo.values()]
    allNodes = ["%s"%(node["extip"]) for node in instInfo.values()]
    for extip, host in zip(extips, sshHosts):
        os.spawnlp(os.P_WAIT,"ssh", "-i", certfile, host,
		   "cd cassandra && git fetch origin && git pull origin master && python scripts/create.py --btport 6881 -n "+(','.join(allNodes))+" -d ~/"+basedir)

    create.setupDirectory("%s/cli"%(basedir,),
			  create.Port(8000, "127.0.0.1", 6881),
			  allNodes,
			  isCli=True)

    startAllScript = "%s/startall.sh"%(basedir, )
    with open(startAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHost in %s; do
    ssh -i %s $sshHost '%s/startup.sh'
done
"""%(' '.join(allNodes), certfile, basedir))
    os.chmod(startAllScript, 0755)
    stopAllScript = "%s/stopall.sh"%(basedir, )
    with open(stopAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHosts in %s; do
    ssh -i %s $sshHost '%s/stop.sh'
done
"""%(' '.join(allNodes), certfile, basedir))
    os.chmod(stopAllScript, 0755)

if __name__=="__main__":
    parser = optparse.OptionParser()
    parser.add_option("-d", "--dir", dest="dir", help="Top-level directory for all nodes")
    parser.add_option("-c", "--clusterid", default=None, dest="clusterid", help="Cluster ID (default 0).")
    parser.add_option("-k", "--key", "--cert", dest="certfile", help="SSH Private Key (.pem)")
    parser.add_option("-n", "--nocreate", dest="nocreate", action="store_true", help="Just get node list")
    (options, args) = parser.parse_args()

    if not options.dir or not options.certfile:
        print >>sys.stderr, "Must specify number of nodes (-n) and directory (-d)"
        parser.print_help()
        sys.exit(1)

    instanceInfo = getInstanceInfo(options.clusterid)
    print "Instances:"
    for nodename,dic in instanceInfo.iteritems():
        print "Name: %s" % (nodename,)
        for k,v in dic:
            print "    %s: %s"%(k,v)
    if not options.nocreate:
        createInstances(options.dir, instnaceInfo, options.certfile)

