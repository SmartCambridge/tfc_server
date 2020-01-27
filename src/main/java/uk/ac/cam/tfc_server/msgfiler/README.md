## [Intelligent City Platform](https://github.com/SmartCambridge/tfc_server) &gt; MsgFiler

MsgFiler is the Intelligent City Flatform data filer that stores high-volume sensor data in the file
system.

The Platform has multiple instances of the MsgFilers running, and we also run multiple concurrent instances
of the entire Platform, so we have data redundantly stored.

The MsgFiler is not intended as an alternative to Vertx Verticles that store EventBus message data in
relational databases or no-sql databases (we've tried them all) but it provides a useful one-size-fits-all
persistence utility which allows trivially simple access to the data.  Our recommendation is to use MsgFiler
*as well as* your preferred time-series / hyper-cube indexing / internet-distributed data store which are rarely
an ideal general solution.

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

The data as sent on the EventBus, partularly if it originated in a FeedMaker, will have the
original incoming sensor data in a `request_data` property which is *always* a JsonArray of
JsonObjects, even if the incoming data was a single data sample from a single sensor. This is to
help with the common situation of each data message containing readings from multiple sensors in
the same message.  Downstream verticles (including the MsgFiler) can efficiently deal with the
sensor readings as a 'batch', or 'shred' the `request_data` array into multiple readings and
deal with each separately.

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

## FeedMaker properties that assist the MsgFiler

The FeedMaker verticle is designed to ingest the incoming real-time data (which can be in any format), persist it
to the filesystem (as a safety measure), apply a custom 'parser' to the data to convert it to a JSON format
suitable for publishing on the EventBus, and sending the message onwards via the EventBus.

Consequently, the FeedMaker can inject properties into the EventBus message which make it easier for downstream
verticles including the MsgFiler to deal with the data. In particular the FeedMaker will add 'root' level
properties to the message including:

`ts` - the Unix timestamp (in seconds) when the data was received

`filepath` - typically `YYYY/MM/DD` where it stored the data in the `data_bin` directory

`filename` - typically `<unix timestamp>_YYYY-mm-DD-HH-MM-SS.<file_suffix>`

These can be used by the MsgFiler (see `{{}}` substitution examples into filenames and paths below)

## MsgFiler example configs

### Simplest

This MsgFiler config will subscribe to messages on the `tfc.feedmaker.mysensors` EventBus address (expected to
come from a FeedMaker). It will store each incoming message 'as-is' to the file `/media/tfc/mysensors/latest.json`.
I.e. the file will be overwritten each time a new data message is received. Note the data will *always* be in
JSON format because that is what we send around the EventBus. Our FeedHandler/Feedmaker is designed to parse the
incoming data and convert it to JSON before publishing it on the EventBus.

```
{ "source_address": "tfc.feedmaker.mysensors",
  "store_path":     "/media/tfc/mysensors",
  "store_name":     "latest.json",
  "store_mode":     "write"
}
```

### Accumulating the sensor data in the filesystem

Here we can change the filename for each message stored, using a timestamp.  Note we assume the timestamp
is *already stored within the incoming message*, in this case in a property `ts`, rather than generating
the timestamp within the MsgFiler. The use of `{{}}` in the filename means 'substitute the value of this
property from the data message.  Currently the property must be at the 'root' of the JsonObject containing
the incoming message, i.e. it can't be a property within a sub-object.This example will accumulate all
the messages in a single directory, and we will change that in the next example.

```
{ "source_address": "tfc.feedmaker.mysensors",
  "store_path":     "/media/tfc/mysensors",
  "store_name":     "{{ts}}.json",
  "store_mode":     "write"
}
```

### Accumulating the sensor data with a directory-per-day

We can also use `{{}}` substitutions in the `store_path`, and in this example we will also use the `|`
modifier to extract day/month/year from a unix timestamp, e.g. an incoming message may be stored as:
```
/media/tfc/mysensors/2020/01/27/1580134796.json
```

```
{ "source_address": "tfc.feedmaker.mysensors",
  "store_path":     "/media/tfc/mysensors/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}",
  "store_name":     "{{ts}}.json",
  "store_mode":     "write"
}
```

### 'Shredding' an array within the data message into multiple files per data message

This config (for Drakewell BlueTruth data) subscribes to the `tfc.feedmaker.btjourney` address used by the
corresponding FeedMaker, but uses the `msg_type` to filter the incoming messages so only 'location' messages
are received (the FeedMaker also collects 'journeytime' messages).

The message received from the EventBus will be of the form:

```
{ "ts": 1580134796,
   ...
   "request_data": [
       { "sites": [ {"id": "site identifier", site data...},
                    { "id": ..data for another site },
                    ...
                  ]
       }
   ]
}
```

The idea is to 'shred' the `sites` JsonArray and store each data record for each site `id` separately, so in the
config we give the MsgFiler a JSON 'path' to the required records in `records_data` as below.  In addition, when
the `records_data` property is provided, we can also give a `merge_base` property which is a list of properties
from the original EventBus message we would like to include in each of these 'shredded' data records. Every
EventBus message from a FeedMaker contains a 'root' property `ts` which is the Unix timestamp when the original
message was received at the FeedMaker and in this example we inject this into each stored data record.

```
{ "source_address": "tfc.feedmaker.btjourney",
    "source_filter": { "field": "msg_type",
                        "compare": "=",
                        "value": "feed_btjourney_locations"
                    },
    "records_data":   "request_data[0]>sites",
    "merge_base":     [ "ts" ],
    "store_path":     "/media/tfc/btjourney/locations/data_site",
    "store_name":     "{{id}}.json",
    "store_mode":     "write"
}
```

### 'Shredding' an EventBus message and accumulating the results for each sensor by appending a file-per-sensor

Essentially the `"store_mode": "append"` will append this new data element to the end of the file given
as `store_path/store_name`. If you study these configuration parameters below you should be able to work
out the data from Jan 27th 2020 for a sensor with unique `id` "cambridge_coffee_pot" will be accumulated
in a file called:

```
/media/tfc/btjourney/journeytimes/data_link/2020/01/27/cambridge_coffee_pot_2020-01-27.txt
```
The yyyy, MM and dd can be repeated in the filename simply as a convenience if the files are emailed around.

Note that the `.txt` suffix is used because although each incremental data value will be a JSON object, once
appended to each other data file is no longer in JSON format.  However, tools such as `jq` do a very good
job of iterating through files containing multiple JSON objects.

```
                { "source_address": "tfc.feedmaker.btjourney",
                  "source_filter": { "field": "msg_type",
                                     "compare": "=",
                                     "value": "feed_btjourney_journeytimes"
                                   },
                  "records_data":   "request_data[0]>journeytimes",
                  "merge_base":     [ "ts" ],
                  "store_path":     "/media/tfc/btjourney/journeytimes/data_link/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}",
                  "store_name":     "{{id}}_{{ts|yyyy}}-{{ts|MM}}-{{ts|dd}}.txt",
                  "store_mode":     "append"
                }
```
