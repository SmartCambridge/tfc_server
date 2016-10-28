#!/bin/bash

#
# List running TFC processes
#
echo USER PID RSS COMMANDARGS
ps -eo user,pid,rss,args | grep vertx | grep -v grep | awk '{print $1,$2,$3,$6,$9,$NF}'

