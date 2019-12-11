#!/bin/bash

#
# List running TFC processes
#
ps aux | awk '/vertx/ && !/awk/ {print $1,$2,$13,$(NF-1),$(NF)}' | sort -k 4,4 | column -t


