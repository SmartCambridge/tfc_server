#!/bin/bash

trap "echo monitor.sh Signalled; exit 0" 10

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

source $SCRIPT_DIR/../VARS

echo $$ >> $SCRIPT_DIR/monitor.pid


while read x; do

    for SCRIPT in $SCRIPT_DIR/monitor.d/*
    do
	if [ -f $SCRIPT -a -x $SCRIPT ]
	    then
	    $SCRIPT
	    fi
	done

done < <(inotifywait -mq -e close_write "$SCRIPT_DIR/../post_data.bin")

echo monitor.sh normal exit

