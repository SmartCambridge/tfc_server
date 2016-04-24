# start a Vertx shell accessible via "telnet localhost 5000"

vertx run -conf '{"telnetOptions":{"port":5000}}' maven:io.vertx:vertx-shell:3.2.1 -cluster -cluster-port 10099

