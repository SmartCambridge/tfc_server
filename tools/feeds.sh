#!/bin/bash

TODAY=$(date +'%Y/%m/%d')

FEEDS=(
/media/tfc/sirivm_json/data_monitor
/media/tfc/itoworld/sirivm/data_monitor
/media/tfc/itoworld/sirivm/data_monitor_json
/media/tfc/itoworld/sirivm/data_zone/$TODAY
/media/tfc/cloudamber/sirivm/data_zone/$TODAY
/media/tfc/cam_park_rss/data_monitor
/media/tfc/cam_park_rss/data_monitor_json
/media/tfc/csn_ttn/data_monitor
/media/tfc/google_traffic_map/cambridge/$TODAY
/media/tfc/btjourney/journeytimes/data_monitor
/media/tfc/btjourney/journeytimes/data_monitor_json
)

date
for f in ${FEEDS[@]}
do
    ts=$(stat --printf='%Z' $f)
    printf "%12s %s\n" "$(date -d @$ts)" $f
done


