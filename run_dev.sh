#!/bin/bash

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.console.A" -cluster -cluster-port 10081 >/dev/null 2>>/home/ijl20/log/console.A.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedhandler.A" -cluster -cluster-port 10080 >/dev/null 2>>/home/ijl20/log/feedhandler.A.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.rita.cambridge" -cluster -cluster-port 10098 >/dev/null 2>>/home/ijl20/log/rita.cambridge.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedcsv.A" -cluster -cluster-port 10082 >/dev/null 2>>/home/ijl20/log/feedcsv.A.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgfiler.test" -cluster -cluster-port 10097 >/dev/null 2>>/home/ijl20/log/msgfiler.test.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.staticserver.A" -cluster -cluster-port 10083 >/dev/null 2>>/home/ijl20/log/staticserver.A.err & disown

java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.dataserver.A" -cluster -cluster-port 10084 >/dev/null 2>>/home/ijl20/log/dataserver.A.err & disown

nohup java -cp target/tfc_server-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgfiler.feed_A_json" -cluster -cluster-port 10085 >/dev/null 2>>/home/ijl20/log/msgfiler.feed_A_json.err & disown

 nohup /home/ijl20/tfc_monitor/tfc_monitor.sh /home/ijl20/tfc_server_data/data_monitor_json/  >>/home/ijl20/log/tfc_monitor.log & disown
 
