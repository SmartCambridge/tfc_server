#!/bin/bash

java -jar target/tfc_server-1.0-SNAPSHOT-fat.jar -cluster -cluster-port 10090 -conf src/main/conf/tfc_server.conf
