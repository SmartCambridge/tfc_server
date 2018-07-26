#!/usr/bin/env python3

'''
Utility to reverse a zone definition, e.g. to create an 'out' zone
definition from the corresponding 'in' one. This script:

1) Reads input from a file named on the command line, or stdin

2) Prints it's results to stdout

3) 'rotates' the 'zone.path' so that the previous finish segment
comes first, and updates zone.finish_index to point to the new
finish segment (what was the old start one)

2) Where possible, updates 'module.id', 'zone.id', 'zone.reverse_id',
and 'zone.name' by swapping any trailing '_in' and '_out', and ' IN' and
' OUT'. This only work for ids and names that conform to the common
convention. A warning message is printed to stderr if a conversion isn't
possible. Conversions  are only attempted if the corresponding parameter
exists. '''

from collections import OrderedDict, deque
import json
import sys

REPLACEMENTS = [['_out', '_in'],
                ['_in', '_out'],
                [' IN', ' OUT'],
                [' OUT', ' IN']]


def fixup(string):
    '''
    Return string with any suffix in REPLACEMENTS[][0] replaced
    by the corresponding REPLACEMENTS[][1]. Return string unchanged
    if here are no matches
    '''
    for choice in REPLACEMENTS:
        if string.endswith(choice[0]):
            return string[:-len(choice[0])] + choice[1]
    print('No automatic update possible for %s' % (id), file=sys.stderr)
    return string


# Read from a file named on the command line, or stdin
if len(sys.argv) > 1:
    input = open(sys.argv[1])
else:
    input = sys.stdin

data = json.load(input, object_pairs_hook=OrderedDict)
config = data['options']['config']

#print(json.dumps(data, indent=4))

# Rotate zone.path, update xone.finish_index
finish_index = config['zone.finish_index']
points = deque(config['zone.path'])
points.rotate(-finish_index)
finish_index = len(points) - finish_index
config['zone.path'] = list(points)
config['zone.finish_index'] = finish_index

# Update module.id, zone.id and zone.name if possible
if 'module.id' in config:
    config['module.id'] = fixup(config['module.id'])

if 'zone.id' in config:
    new_id = fixup(config['zone.id'])
    if new_id != config['zone.id']:
        config['zone.reverse_id'] = config['zone.id']
        config['zone.id'] = new_id

if 'zone.name' in config:
    config['zone.name'] = fixup(config['zone.name'])

print(json.dumps(data, indent=4))
