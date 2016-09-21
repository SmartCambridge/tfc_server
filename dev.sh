#!/bin/bash

mvn package

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.console.dev" -cluster -cluster-port 10081 >/dev/null 2>>/home/ijl20/log/console.A.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedhandler.dev" -cluster -cluster-port 10080 >/dev/null 2>>/home/ijl20/log/feedhandler.A.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.rita.dev" -cluster -cluster-port 10098 >/dev/null 2>>/home/ijl20/log/rita.cambridge.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.staticserver.dev" -cluster -cluster-port 10083 >/dev/null 2>>/home/ijl20/log/staticserver.A.err & disown

#java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.dataserver.dev" -cluster -cluster-port 10084 >/dev/null 2>>/home/ijl20/log/dataserver.A.err & disown

#nohup /home/ijl20/tfc_monitor/tfc_monitor.sh /home/ijl20/tfc_server_data/data_monitor_json/  >>/home/ijl20/log/tfc_monitor.log & disown
