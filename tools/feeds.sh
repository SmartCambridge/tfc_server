#!/bin/bash

TODAY=$(date +'%Y/%m/%d')

FEEDS=(
/media/tfc/cam_park_rss/data_monitor
/media/tfc/cam_park_rss/data_monitor_json
/media/tfc/csn_ttn/data_monitor
/media/tfc/btjourney/journeytimes/data_monitor
/media/tfc/btjourney/journeytimes/data_monitor_json
/media/tfc/sirivm_json/data_monitor
/media/tfc/itoworld/sirivm_json/data_monitor
/media/tfc/itoworld/sirivm/data_monitor
/media/tfc/itoworld/sirivm/data_monitor_json
)

date
for f in ${FEEDS[@]}
do
    ts=$(stat --printf='%Z' $f)
    printf "%12s %s\n" "$(date -d @$ts)" $f
done

# Get the timestamp of the most recently updated zone file
f=$(ls --sort=time /media/tfc/itoworld/sirivm/data_zone/$TODAY/*.txt | head -n 1)
ts=$(stat --printf='%Z' $f)
printf "%12s %s\n" "$(date -d @$ts)" $f

# Google Traffic Map
d='/media/tfc/google_traffic_map/'
if [ -d $d ]
then
    f="/media/tfc/google_traffic_map/cambridge/$TODAY"
    ts=$(stat --printf='%Z' $f)
    printf "%12s %s\n" "$(date -d @$ts)" $f
else
    echo "No Google Traffic Maps directory on this server"
fi

