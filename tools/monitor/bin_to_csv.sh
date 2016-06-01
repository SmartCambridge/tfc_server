#!/bin/bash

../parse/csv_file.py $TFC_DATA_CACHE/post_data.bin $TFC_DATA_CACHE/data.csv

cp $TFC_DATA_CACHE/data.csv $TFC_DATA_HTML

