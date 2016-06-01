#!/bin/bash

echo $2 | mail -s "$1" ijl20@cam.ac.uk

echo $(date) mail_alert.sh status email sent. $1  >>mail_alert.log

