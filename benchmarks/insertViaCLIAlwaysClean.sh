#!/bin/bash

function get_file_list () {
  str=""
  for x in $1; do
    if [[ x"$str" = x"" ]]; then
      str=${x}
    else
      str=${str},${x}
    fi
  done
  echo $str
}

function get_column_names () {
  str=""
  prefix=$1
  for file in $2; do
    x=${prefix}_$(basename ${file})
    if [[ x"$str" = x"" ]]; then
      str=${x}
    else
      str=${str},${x}
    fi
  done
  echo $str
}

function timed_cmd () {
  echo "Running CLI with input:" >&2
  echo "$1" >&2
  (echo "$1"; echo "quit") | ec2/cli/cli.sh | grep real | cut -d' ' -f2
}

DATADIR=$PWD/testdata
Iterations="1 2 3 4 5"
SIZES="1 64k 128k 256k 512k"

function cleanup() {
  killall -9 java utserver
  rm -f ec2/cli/torrents/*
  rm -f ec2/cli/downloads/*
  rm -f ec2/cli/*.dat*
  rm -rf ec2/active-data/*
  cp -rf active-data/* ec2/active-data/
}

function remote_cleanup() {
  ec2/stopall.sh --delete
  ec2/commandonall.sh 'killall -9 java utserver; rm -f ec2/downloads/*; rm -f ec2/torrents/*; rm -rf ec2/active-data/*; cp -rf poseidon/active-data/* ec2/active-data/; rm -f ec2/*.dat*; rm -f poseidon/*.hconf' 
  ec2/startall.sh
}

mkdir $DATADIR
mkdir $DATADIR/orig

for kbytes in $SIZES; do
  dd if=/dev/urandom of=$DATADIR/orig/$kbytes bs=1k count=${kbytes}
done

function hardlinkdata () {
  for kbytes in $SIZES; do
    ln $DATADIR/orig/$kbytes $DATADIR/$kbytes
  done
}

function print_bandwidth () {
    echo cli
    /sbin/ifconfig eth0 | grep "RX bytes" | sed -e "s/.*RX bytes:\\([0-9]*\\) .*/name=$1, rx=\\1/"
    echo cli
    /sbin/ifconfig eth0 | grep "TX bytes" | sed -e "s/.*TX bytes:\\([0-9]*\\) .*/name=$1, tx=\\1/"
    ec2/commandonall.sh '/sbin/ifconfig eth0 | grep "RX bytes" | sed -e "s/.*RX bytes:\\([0-9]*\\) .*/name='"$1"', rx=\\1/"'
    ec2/commandonall.sh '/sbin/ifconfig eth0 | grep "TX bytes" | sed -e "s/.*TX bytes:\\([0-9]*\\) .*/name='"$1"', tx=\\1/"'
}

remote_cleanup
cleanup
#if false; then ##############
for i in $Iterations; do 
  hardlinkdata
  for kbytes in $SIZES; do
    echo Iteration $i: $kbytes
    value=$(get_file_list ${DATADIR}/${kbytes})
    normalColumns=$(get_column_names _F ${DATADIR}/${kbytes})
    torrentColumns=$(get_column_names __T_F ${DATADIR}/${kbytes})

    echo "Value: $value"
    echo "Normal Columns: $normalColumns"
    echo "Torrent Columns: $torrentColumns"

    print_bandwidth set-before-normal
    normalDuration=$(timed_cmd "set Keyspace1.Standard1['benchmark_${i}_${kbytes}']['${normalColumns}'] = '${value}' ALL")
    print_bandwidth set-after-normal
    torrentDuration=$(timed_cmd "set Keyspace1.Standard1['benchmark_${i}_${kbytes}']['${torrentColumns}'] = '${value}' ALL")
    print_bandwidth set-after-torrent
    remote_cleanup
    cleanup

    echo -e "set\t$kbytes\t$normalDuration\t$torrentDuration"
  done
done

hardlinkdata
for kbytes in $SIZES; do
    value=$(get_file_list ${DATADIR}/${kbytes})
    normalColumns=$(get_column_names _F ${DATADIR}/${kbytes})
    torrentColumns=$(get_column_names __T_F ${DATADIR}/${kbytes})

    $(timed_cmd "set Keyspace1.Standard1['benchmark_${kbytes}']['${normalColumns}'] = '${value}' ALL")
    $(timed_cmd "set Keyspace1.Standard1['benchmark_${kbytes}']['${torrentColumns}'] = '${value}' ALL")
done
#fi ################

hardlinkdata
for i in $Iterations; do 
  for kbytes in $SIZES; do
    cleanup

    normalColumns=$(get_column_names _F ${DATADIR}/${kbytes})
    torrentColumns=$(get_column_names __T_F ${DATADIR}/${kbytes})

    print_bandwidth get-before-normal
    normalDuration=$(timed_cmd "get Keyspace1.Standard1['benchmark_${kbytes}']['${normalColumns}'] ALL")
    print_bandwidth get-after-normal
    torrentDuration=$(timed_cmd "get Keyspace1.Standard1['benchmark_${kbytes}']['${torrentColumns}'] ALL")
    print_bandwidth get-after-torrent

    echo -e "get\t$kbytes\t$normalDuration\t$torrentDuration"
  done
done

remote_cleanup
cleanup
