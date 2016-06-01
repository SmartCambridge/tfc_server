#!/bin/bash

curl --insecure --silent -X POST --data-binary "@$TFC_DATA_CACHE/post_data.bin" https://tfcfeed.cl.cam.ac.uk/feed > /dev/null

