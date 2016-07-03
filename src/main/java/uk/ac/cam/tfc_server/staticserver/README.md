# [Rita](https://github.com/ijl20/tfc_server) &gt; StaticServer

StaticServer is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

StaticServer is a basic http server for serving static content from the filesystem,
useful for the Rita platform when running behind an Nginx reverse proxy.

StaticServer is configured via Vertx application config(), such as
```
{
    "main": "uk.ac.cam.tfc_server.staticserver.StaticServer",
    "options": {
        "config": {
            "module.name":      "staticserver",
            "module.id":        "A",

            "eb.system_status": "tfc.system_status",
            "eb.console_out":   "tfc.console_out",
            "eb.manager":       "tfc.manager",

            "staticserver.http.port":  8083,
            "staticserver.webroot":   "webroot"
        }
    }
}
```

StaticServer is complemented with DataServer which serves server-side scripted templated pages.


