#!/bin/bash
#
# Usage:
#  ./run.sh [optional JAR filename]
#
# If no jar filename is given, "./tfc.jar" will be used.
#
# run.sh - run a working set of Adaptive City Platform modules in 'production' mode
#
# start vix modules

# If an argument has been given, use tfc<argument>.jar, e.g. ./run.sh _2017-03-31, and this will use tfc_2017-03-31.jar
# Otherwise run.sh will simply use tfc.jar

# Find the directory this script is being run from, because that will contain the JAR files
# typically "/home/tfc_prod/tfc_prod/"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# set jar filename to arg given on command line OR default to "tfc.jar"
TFC_JAR=${1:-tfc.jar}

cd $SCRIPT_DIR

# load secrets from secrets.sh if it exists - includes RTMONITOR_KEY
SECRETS_FILE=$SCRIPT_DIR/secrets.sh && test -f $SECRETS_FILE && source $SECRETS_FILE

# CONSOLE
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.console.A" -cluster >/dev/null 2>>/var/log/tfc_prod/console.A.err & disown

# DATASERVER TO PROVIDE DATA API FOR TFC_WEB
#nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.dataserver.vix" -cluster >/dev/null 2>>/var/log/tfc_prod/dataserver.vix.err & disown

# #############################################################################################
# ################  PARK_LOCAL_RSS FEEDMAKER  #################################################
# #############################################################################################

# FEEDMAKER TO SCRAPE CAR PARK WEB SITES
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedmaker.park_rss" -cluster >/dev/null 2>>/var/log/tfc_prod/feedmaker.park_rss.err & disown

# MSGFILER TO STORE MESSAGES FROM CAR PARKS FEEDMAKER (i.e. from feedmaker.park_local_rss)
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgfiler.cam.to_json" -cluster >/dev/null 2>>/var/log/tfc_prod/msgfiler.cam.to_json.err & disown

# #############################################################################################
# ################  EVERYNET FEED HANDLER  ####################################################
# #############################################################################################

# EVERYNETFEED (receives http PUSH sensor data messages from EveryNet)
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.everynet_feed.A" -cluster >/dev/null 2>>/var/log/tfc_prod/everynet_feed.A.err & disown

# MSGROUTER (forwards EveryNet messages to onward destinations)
#nohup java -cp "postgresql-42.1.3.jar:$TFC_JAR" io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgrouter.A" -cluster >/dev/null 2>>/var/log/tfc_prod/msgrouter.A.err & disown

# HTTPMSG (command API for tfc_web)
#nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.httpmsg.A" -cluster >/dev/null 2>>/var/log/tfc_prod/httpmsg.A.err & disown

# #############################################################################################
# ################   VIX GTFS FEEDMAKER  ######################################################
# #############################################################################################

# VIX2 FEEDMAKER FOR GTFS VIX DATA
#nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedmaker.vix2" -cluster >/dev/null 2>>/var/log/tfc_prod/feedmaker.vix2.err & disown

# VIX2 ZONEMANAGER FOR GTFS VIX DATA
#nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.zonemanager.vix2" -cluster >/dev/null 2>>/var/log/tfc_prod/zonemanager.vix2.err & disown

# VIX2 MSGFILER FOR GTFS VIX DATA AND ZONE TRANSITS
#nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgfiler.vix2" -cluster >/dev/null 2>>/var/log/tfc_prod/msgfiler.vix2.err & disown

# #############################################################################################
# ################   SIRIVM CLOUDAMBER FEEDMAKER  #############################################
# #############################################################################################

# SIRIVM FEEDMAKER FOR CLOUDAMBER SIRIVM AND SIRIVM_JSON DATA
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.feedmaker.A" -cluster >/dev/null 2>>/var/log/tfc_prod/feedmaker.A.err & disown

# SIRIVM ZONEMANAGER FOR CLOUDAMBER SIRIVM DATA
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.zonemanager.cloudamber.sirivm" -cluster >/dev/null 2>>/var/log/tfc_prod/zonemanager.cloudamber.sirivm.err & disown

# SIRIVM MSGFILER FOR CLOUDAMBER SIRIVM DATA
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgfiler.cloudamber.sirivm" -cluster >/dev/null 2>>/var/log/tfc_prod/msgfiler.cloudamber.sirivm.err & disown

nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgrouter.cloudamber" -cluster >/dev/null 2>>/var/log/tfc_prod/msgrouter.cloudamber.err & disown

# #############################################################################################
# ################   TTN MQTT FEED HANDLER        #############################################
# #############################################################################################

nohup java -cp "$TFC_JAR:secrets" -Xmx100m -Xms10m -Xmn2m -Xss10m io.vertx.core.Launcher run "service:feedmqtt.ttn" -cluster >/dev/null 2>>/var/log/tfc_prod/feedmqtt.ttn.err & disown

# #############################################################################################
# ################   DRAKEWELL BTJOURNEY FEED                ##################################
# #############################################################################################

nohup java -cp "$TFC_JAR:secrets" -Xmx100m -Xms10m -Xmn2m -Xss10m io.vertx.core.Launcher run "service:feedmaker.btjourney" -cluster >/dev/null 2>>/var/log/tfc_prod/feedmaker.btjourney.err & disown

nohup java -cp "$TFC_JAR" -Xmx100m -Xms10m -Xmn2m -Xss10m io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.msgfiler.btjourney" -cluster >/dev/null 2>>/var/log/tfc_prod/msgfiler.btjourney.err & disown

# #############################################################################################
# ################   RTMONITOR                    #############################################
# #############################################################################################

# RTMONITOR.SIRIVM
nohup java -cp $TFC_JAR io.vertx.core.Launcher run "service:uk.ac.cam.tfc_server.rtmonitor.sirivm" -cluster >/dev/null 2>>/var/log/tfc_prod/rtmonitor.sirivm.err & disown

