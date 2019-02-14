#!/bin/bash

source secrets.sh;java -cp "target/tfc_server-3.5.2-fat.jar:dev_configs" -Xmx100m -Xms10m -Xmn2m -Xss10m io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.rtmonitor.app3" -cluster
