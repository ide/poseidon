#!/usr/bin/python
from __future__ import with_statement

import create
import sys
import os
import optparse
from subprocess import Popen, PIPE

def getInstanceInfo(region,cluster=None):
    output = ''
    for r in region:
        pipe = Popen(["ec2-describe-instances","--region",r], stdout=PIPE)
        output += pipe.communicate()[0]
    print output
    instances = {}
    for line in output.split("\n"):
        if not line:
            continue
        info = line.split("\t")
        if info[0]=="INSTANCE":
            id = info[1]
            if id in instances:
                print output
                raise ValueError("Duplicate Instance: %s"%(id,))
            datacenter = info[11]
            state = info[5]
            extip = info[16]
            intip = info[17]
            user = "ec2-user"
            instances[id] = {"tag": {}, "extip": extip, "intip": intip, "datacenter": datacenter, "state": state, "user": user}
        if info[0]=="TAG":
            id = info[2]
            key = info[3]
            value = info[4]
            instances[id]["tag"][key] = value
    return dict((k,v) for (k,v) in instances.iteritems() if (not cluster or v["tag"].get("Cluster","")==cluster) and v["state"]=="running")

def createInstances(basedir, instInfo, certfile):
    try:
        os.mkdir(basedir)
    except OSError:
        pass
    sshHosts = ["%s@%s"%(node["user"],node["extip"]) for node in instInfo.values()]
    allNodes = ["%s"%(node["extip"]) for node in instInfo.values()]
    for node, sshHost in zip(allNodes, sshHosts):
        args = ["ssh", "ssh", "-i", certfile, sshHost,
                "cd poseidon && git fetch origin && git pull origin master && ant && python scripts/create.py --btport 6881 -n "+(','.join(allNodes))+" -d ~/"+basedir+" --hostip "+node]
        print args
        os.spawnlp(os.P_WAIT, *args)

    create.setupDirectory(os.path.join(os.getcwd(),basedir,'cli'),
			  create.Port(8000, "0.0.0.0", "", create.BT_PORT),
			  [create.Port(create.DEFAULT_PORT, n, n, create.BT_PORT) for n in allNodes],
			  isCli=True)

    restartAllScript = "%s/restartall.sh"%(basedir,)
    with open(restartAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHost in %s; do
    echo $sshHost
    ssh -i %s $sshHost '%s/restart.sh'" $*"
done
"""%(' '.join(sshHosts), certfile, basedir))
    os.chmod(restartAllScript, 0755)
    startAllScript = "%s/startall.sh"%(basedir,)
    with open(startAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHost in %s; do
    echo $sshHost
    ssh -i %s $sshHost '%s/startup.sh'" $*"
done
"""%(' '.join(sshHosts), certfile, basedir))
    os.chmod(startAllScript, 0755)
    stopAllScript = "%s/stopall.sh"%(basedir, )
    with open(stopAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHost in %s; do
    echo $sshHost
    ssh -i %s $sshHost '%s/stop.sh'" $*"
done
"""%(' '.join(sshHosts), certfile, basedir))
    os.chmod(stopAllScript, 0755)
    stopAllScript = "%s/commandonall.sh"%(basedir, )
    with open(stopAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHost in %s; do
    echo $sshHost
    ssh -i %s $sshHost "$1"
done
"""%(' '.join(sshHosts), certfile))
    os.chmod(stopAllScript, 0755)
    stopAllScript = "%s/rsyncall.sh"%(basedir, )
    with open(stopAllScript, 'w') as script:
        script.write("""#!/bin/bash
for sshHost in %s; do
    echo $sshHost
    rsync -av -e "ssh -i %s" $1 "${sshHost}:$2"
done
"""%(' '.join(sshHosts), certfile))
    os.chmod(stopAllScript, 0755)

if __name__=="__main__":
    parser = optparse.OptionParser()
    parser.add_option("-r", "--regions", dest="regions", help="Regions to use separated by commas (i.e. us-west-1). See ec2-describe-regions")
    parser.add_option("-d", "--dir", dest="dir", help="Top-level directory for all nodes. *MUST* be relative path")
    parser.add_option("-c", "--clusterid", default=None, dest="clusterid", help="Cluster ID (default 0).")
    parser.add_option("-k", "--key", "--cert", dest="certfile", help="SSH Private Key (.pem)")
    parser.add_option("-l", "--list", "--nocreate", dest="nocreate", action="store_true", help="Just get node list")
    (options, args) = parser.parse_args()

    if not options.regions:
        options.regions = 'eu-west-1,us-east-1,us-west-1,ap-southeast-1'

    instanceInfo = getInstanceInfo(options.regions.split(','), options.clusterid)
    print "Instances:"
    for nodename,dic in instanceInfo.iteritems():
        print "Name: %s" % (nodename,)
        for k,v in dic.iteritems():
            print "    %s: %s"%(k,v)
    if not options.nocreate:
        if not options.dir or not options.certfile:
            print >>sys.stderr, "Must specify private key (-k) and directory (-d) and regions(-r)"
            parser.print_help()
            sys.exit(1)

        createInstances(options.dir, instanceInfo, options.certfile)

