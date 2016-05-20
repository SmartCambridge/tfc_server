                                                                                
{
    "main": "uk.ac.cam.tfc_server.rita.Rita",
    "options": {
        "config": {
            "module.name":      "rita",
            "module.id":        "backs_test",

            "eb.system_status": "tfc.system_status",
            "eb.console_out":   "tfc.console_out",
            "eb.manager":       "tfc.manager",

            "rita.address":     "tfc.rita.backs_test",

            "rita.feedplayers": [ "2016-03-07-week" ],
            "rita.zonemanagers": [ "the_backs_south" ],
            "rita.feedplayer.address":    "tfc.feedplayer.2016-03-07-week",
            "rita.zone.address": "tfc.zone.2016-03-07-week",
            "rita.zone.feed": "tfc.feedplayer.2016-03-07-week",

            "rita.name":      "RITA-2016-03-07-week",
            "rita.http.port":  8099,
            "rita.webroot":   "src/main/java/uk/ac/cam/tfc_server/rita/webroot"
        }
    }
}
