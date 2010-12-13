#!/bin/bash

DATADIR=$1
SIZES="$2"

mkdir $DATADIR

for kbytes in $SIZES; do
  mkdir $DATADIR/$kbytes
  for x in {0..3}; do
    dd if=/dev/urandom of=$DATADIR/$kbytes/data$x bs=1k count=${kbytes}
  done
done


