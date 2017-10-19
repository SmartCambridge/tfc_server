#!/bin/bash

#
# List running TFC processes
#
ps aux | awk '/vertx/ && !/awk/ {print $1,$2,$13,$16}' | sort -k 4,4 | column -t


