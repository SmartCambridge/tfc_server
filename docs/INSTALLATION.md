## Installation

Install Java 8

ufw allow 5701:6701/tfc
ufw allow 54327/udp

## Run in production

 nohup java -cp tfc.jar io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.console.A" -cluster -cluster-port 10081 >/dev/null 2>>/home/ijl20/log/tfc_console.A.err &

jobs

disown %1

ps aux | grep cluster
