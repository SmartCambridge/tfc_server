java -cp "target/tfc_server-3.6.3-fat.jar:dev_configs:secrets" -Xmx100m -Xms10m -Xmn2m -Xss10m io.vertx.core.Launcher run "service:batcher"
