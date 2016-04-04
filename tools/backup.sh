#!/bin/bash

DATE=`date +%Y-%m-%d`

mkdir /media/dsfiles/IJL20/tfc_backups/tfc_dev_$DATE

cp $HOME/tfc_dev/* /media/dsfiles/IJL20/tfc_backups/tfc_dev_$DATE

cp -r $HOME/tfc_dev/tools /media/dsfiles/IJL20/tfc_backups/tfc_dev_$DATE/tools

cp -r $HOME/tfc_dev/src /media/dsfiles/IJL20/tfc_backups/tfc_dev_$DATE/src

echo backup of tfc_dev, tools, src to dsfiles/tfc_backups completed

