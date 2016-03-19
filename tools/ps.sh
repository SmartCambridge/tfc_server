#!/bin/bash

#
# List running TFC processes
#
ps aux | grep '\-cluster' | grep -v grep | awk '{print $2,$(NF-3),$NF}'
