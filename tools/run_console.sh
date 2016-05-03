#!/bin/bash

# run the primary Console instance serving http port 8081, on cluster-port 10081

nohup vertx run "service:uk.ac.cam.tfc_server.console.A" -cluster -cluster-port 10081 >/dev/null 2>>/home/ijl20/tfc_server_data/log/console.err &

