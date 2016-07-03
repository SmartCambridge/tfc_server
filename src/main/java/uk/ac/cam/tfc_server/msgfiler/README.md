# [Rita](https://github.com/ijl20/tfc_server) &gt; MsgFiler

MsgFiler is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

MsgFiler subscribes to an eventbus address, filters the messages, and stores them
as text in the filesystem.

Vertx config() parameters specify the message selection / storage criteria, e.g. as below (from
[uk.ac.cam.tfc_server.msgfiler.zone_cambridge.json](https://github.com/ijl20/tfc_server/main/resources/uk.ac.cam.tfc_server.msgfiler.zone_cambridge.json))
```
{
    "main":    "uk.ac.cam.tfc_server.msgfiler.MsgFiler",
    "options":
        { "config":
          {

            "module.name":           "msgfiler",
            "module.id":             "zone_cambridge",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",

            "msgfiler.address": "tfc.msgfiler.zone_cambridge",

            "msgfiler.filers":
            [
                { "source_address": "tfc.zone.cambridge",
                  "source_filter": { "field": "msg_type",
                                     "compare": "=",
                                     "value": "zone_completion"
                                   },
                  "store_path": "/media/tfc/vix/data_zone/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}",
                  "store_name": "{{module_id}}_{{ts|yyyy}}-{{ts|MM}}-{{ts|dd}}.txt",
                  "store_mode": "append"
                }
            ]
              
          }
        }
}
```

MsgFiler will apply a 'filter' to the messages received to decide which ones worthy of storing, in the
form of a very simple field / compare / value template, where field is the name of an attribute of the 
message JSON, compare can be =, >, < or 'contains'.

The store_path / store_name support embedded parameter substitition between paired double curly brackets:

```
{{<field_name>}}, populated via msg.getString(field_name), e.g. {{module_id}}
{{<field_name>|int}}, populated via msg.getLong(field_name)
{{<field_name>|yyyy}}, get msg.getLong(field_name), parse it as a Unix timestamp, return year as "yyyy"
{{<field_name>|MM}}, get msg.getLong(field_name), parse it as a Unix timestamp, return month as "MM"
{{<field_name>|dd}}, get msg.getLong(field_name), parse it as a Unix timestamp, return day of month as "dd"
```
MsgFiler can either create a new file for each message (store_mode = write) or can append to an existing file, as above.

MsgFiler will create directories in the path where they don't already exist, this is convenient when you have the date
dynamically embedded in the store_path so directories for a new day will automatically be created.
