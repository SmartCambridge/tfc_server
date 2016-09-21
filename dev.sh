#!/bin/bash

mvn package

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.console.dev" -cluster -cluster-port 10081 >/var/log/tfc/console.log 2>>/var/log/tfc/console.error.log & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedhandler.dev" -cluster -cluster-port 10080 >/var/log/tfc/feedhandler.log 2>>/var/log/tfc/feedhandler.error.log & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.rita.dev" -cluster -cluster-port 10098 >/var/log/tfc/rita.log 2>>/var/log/tfc/rita.error.log & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.staticserver.dev" -cluster -cluster-port 10083 >/var/log/tfc/staticserver.log 2>>/var/log/tfc/staticserver.error.log & disown

#java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.dataserver.dev" -cluster -cluster-port 10084 >/var/log/tfc/dataserver.log 2>>/var/log/dataserver.error.log & disown

#nohup /home/ijl20/tfc_monitor/tfc_monitor.sh /home/ijl20/tfc_server_data/data_monitor_json/  >>/home/ijl20/log/tfc_monitor.log & disown
