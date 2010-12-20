#!/usr/bin/python
import sys
import re
ip_regex=re.compile("^[-a-z0-9]*@(([0-9]*)\.([0-9]*)\.([0-9]*)\.([0-9]*))$")
bandwidth_regex=re.compile("^name=([sg]et)-([-a-z]*), ([rt]x)=([0-9]*)$")
size_regex=re.compile("^Iteration ([0-9]*): ([0-9]*k*)$")
time_regex=re.compile("^([gs]et)\t([0-9]*k*)\t([0-9.]*)\t([0-9.]*)$")

tests = {"get":{"bw_normal":{},"bw_torrent":{},"pct_normal":{},"pct_torrent":{}},
         "set":{"bw_normal":{},"bw_torrent":{},"pct_normal":{},"pct_torrent":{}}}

if len(sys.argv) <= 1:
    print >>sys.stderr, "Usage: %s benchmark-output..."%(sys.argv[0])
    sys.exit(1)

for a in sys.argv[1:]:
    with open(a) as f:
        host = None
        size = None
        deltas = {}
        current = {"rx": {}, "tx": {}}
        contains_bandwidth = False
        for line in f.readlines():
            ip_match = ip_regex.match(line)
            bandwidth_match = bandwidth_regex.match(line)
            size_match = size_regex.match(line)
            time_match = time_regex.match(line)
            if ip_match:
                host = ip_match.group(1) # different for each ec2 region
            elif line.strip() == "cli":
                host = "cli"
            elif bandwidth_match:
                contains_bandwidth = True
                test_part = bandwidth_match.group(2)
                rxtx = bandwidth_match.group(3)
                last_data = current[rxtx].get(host, 0)
                next_data = long(bandwidth_match.group(4))
                current[rxtx][host] = next_data
                delta = next_data - last_data
                assert delta > 0
                if host not in deltas:
                    deltas[host] = {}
                res = deltas[host]
                if test_part == "after-normal":
                    res[rxtx+"_delta_normal"] = delta
                elif test_part == "after-torrent":
                    res[rxtx+"_delta_torrent"] = delta
                host = None
            elif time_match and not contains_bandwidth:
                #print "File "+a+" does not contain bandwidth info"
                pass
            elif time_match:
                test_type = time_match.group(1)
                size = time_match.group(2)
                if size[-1] == "k":
                    size = int(size[:-1])*1024
                size = int(size)*1024
                if size <= 1024:
                    continue

                time_normal = float(time_match.group(3))
                time_torrent = float(time_match.group(4))
                for host in deltas:
                    res = deltas[host]
                    delta_normal = 0
                    delta_torrent = 0
                    for rxtx in ("rx","tx"):
                        delta_normal += float(res[rxtx+"_delta_normal"])
                        delta_torrent += float(res[rxtx+"_delta_torrent"])
                    for key, value in [('bw_normal', (delta_normal / time_normal)),
                                       ('bw_torrent', (delta_torrent / time_torrent)),
                                       ('pct_normal', (delta_normal / size)),
                                       ('pct_torrent', (delta_torrent / size))]:
                        dic = tests[test_type][key]
                        dic[host] = dic.get(host, [])
                        dic[host].append(value)


for getset, getsetdict in tests.iteritems():
    for testtype in getsetdict.iterkeys():
        for host, valuelist in getsetdict[testtype].iteritems():
            for value in valuelist:
                print "%s\t%s\t%s\t%f"%(getset, testtype, host, value)
        #getsetdict[testtype] = reduce((lambda a,b:a+b),getsetdict[testtype].values(),[])

#import numpy
#for getset, getsetdict in tests.iteritems():
#    for testtype, values in getsetdict.iteritems():
#        print getset, testtype, "mean:", numpy.mean(values), "stddev:", numpy.std(values)
