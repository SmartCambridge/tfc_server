#!/bin/bash

curl --silent -X POST -H "Authorization: Bearer qXyuT/9+HtP1AbTQIfMzbn9jaJ1SIfYxwC3GqUsO"  --data-binary "@$TFC_DATA_CACHE/post_data.bin" http://cambus.thingful.net/data > /dev/null

