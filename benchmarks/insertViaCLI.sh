#!/bin/bash

function get_file_list () {
  str=""
  for x in $1/*; do
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
  for file in $2/*; do
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
  (echo "$1"; echo "quit") | ec2/cli/cli.sh | tee -a cli-output.txt | grep real | cut -d' ' -f2
}

DATADIR=$PWD/testdata
Iterations="1 2 3 4 5"
#SIZES="1 4 16 64"
SIZES="16k 32k 64k 128k"

function cleanup() {
  rm -f ec2/cli/torrents/*
  rm -f ec2/cli/downloads/*
  rm -f ec2/cli/*.dat*
}

cleanup
for i in $Iterations; do 
  benchmarks/gendata.sh $DATADIR "$SIZES"

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

    echo -e "set\t$kbytes\t$normalDuration\t$torrentDuration"
  done
done

cleanup
for i in $Iterations; do 
  for kbytes in $SIZES; do
    normalColumns=$(get_column_names _F ${DATADIR}/${kbytes})
    torrentColumns=$(get_column_names __T_F ${DATADIR}/${kbytes})

    normalDuration=$(timed_cmd "get Keyspace1.Standard1['benchmark_${i}_${kbytes}'] ALL")
    torrentDuration=$(timed_cmd "get Keyspace1.Standard1['benchmark_${i}_${kbytes}'] ALL")

    echo -e "get\t$kbytes\t$normalDuration\t$torrentDuration"
  done
done

cleanup
