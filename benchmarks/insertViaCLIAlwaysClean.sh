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
#SIZES="1 4 16 64"
SIZES="64k 128k 256k 512k"

function cleanup() {
  killall -9 java utserver
  rm -f ec2/cli/torrents/*
  rm -f ec2/cli/downloads/*
  rm -f ec2/cli/*.dat*
  rm -rf ec2/active-data/*
  cp -rf poseidon/active-data/* ec2/active-data/
}

function remote_cleanup() {
  ec2/stopall.sh --delete
  ec2/commandonall.sh 'killall -9 java utserver; rm -f ec2/downloads/*; rm -f ec2/torrents/*; rm -rf ec2/active-data/*; cp -rf poseidon/active-data/* ec2/active-data/; rm -f ec2/*.dat*' 
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

remote_cleanup
cleanup
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

    normalDuration=$(timed_cmd "set Keyspace1.Standard1['benchmark_${i}_${kbytes}']['${normalColumns}'] = '${value}' ALL")
    torrentDuration=$(timed_cmd "set Keyspace1.Standard1['benchmark_${i}_${kbytes}']['${torrentColumns}'] = '${value}' ALL")
    remote_cleanup
    cleanup

    echo -e "set\t$kbytes\t$normalDuration\t$torrentDuration"
  done
done

for i in $Iterations; do 
  hardlinkdata
  for kbytes in $SIZES; do
    normalColumns=$(get_column_names _F ${DATADIR}/${kbytes})
    torrentColumns=$(get_column_names __T_F ${DATADIR}/${kbytes})

    normalDuration=$(timed_cmd "get Keyspace1.Standard1['benchmark_${i}_${kbytes}'] ALL")
    $(timed_cmd "set Keyspace1.Standard1['benchmark_${i}_${kbytes}']['${torrentColumns}'] = '${value}' ALL")
    torrentDuration=$(timed_cmd "get Keyspace1.Standard1['benchmark_${i}_${kbytes}'] ALL")

    remote_cleanup
    cleanup

    echo -e "get\t$kbytes\t$normalDuration\t$torrentDuration"
  done
done

remote_cleanup
cleanup
