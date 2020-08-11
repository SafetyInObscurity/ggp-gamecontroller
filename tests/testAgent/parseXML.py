import sys
import xml.etree.ElementTree as ET
import csv
import time
import os.path


def parse_file_csv(filename, csv):
    root = ET.parse(filename).getroot()

    # Extract relevant info
    match_id = ""
    timestamp = ""
    startclock = ""
    sight_of = ""
    num_steps = ""
    role_1 = ""
    player_1 = ""
    player_1_score = ""
    role_2 = ""
    player_2 = ""
    player_2_score = ""

    for child in root:
        # Basic root info
        if(child.tag == "match-id"):
            match_id = child.text
        elif(child.tag == "timestamp"):
            timestamp = child.text
        elif(child.tag == "startclock"):
            startclock = child.text
        elif(child.tag == "sight-of"):
            sight_of = child.text
        elif(child.tag == "role" and role_1 == ""):
            role_1 = child.text
        elif(child.tag == "role"):
            role_2 = child.text
        elif(child.tag == "player" and player_1 == ""):
            player_1 = child.text
        elif(child.tag == "player"):
            player_2 = child.text
        # Get scores
        elif(child.tag == "scores"):
            for reward in child:
                if(player_1_score == ""):
                    player_1_score = reward.text
                else:
                    player_2_score = reward.text
        # Count number of steps taken
        elif(child.tag == "history"):
            num_steps = len(child)

    writer.writerow({'match_id': match_id,
                     'game_name': game_name,
                     'gdl_version': gdl_version,
                     'timestamp': timestamp,
                     'startclock': startclock,
                     'playclock': play_clock,
                     'sight_of': sight_of,
                     'num_steps': num_steps,
                     'role_1': role_1,
                     'player_1': player_1,
                     'player_1_score': player_1_score,
                     'role_2': role_2,
                     'player_2': player_2,
                     'player_2_score': player_2_score})


# Read in from bash
output_dir = sys.argv[1]
file_prefex = sys.argv[2]
test_start_num = int(sys.argv[3])
test_end_num = int(sys.argv[4])
game_name = sys.argv[5]
gdl_version = sys.argv[6]
play_clock = sys.argv[7]

with open(output_dir + 'testOutput_' + str(int(time.time())) + '.csv', mode='w') as csv_file:
    fieldnames = ['match_id',
                  'game_name',
                  'gdl_version',
                  'timestamp',
                  'startclock',
                  'playclock',
                  'sight_of',
                  'num_steps',
                  'role_1',
                  'player_1',
                  'player_1_score',
                  'role_2',
                  'player_2',
                  'player_2_score']
    writer = csv.DictWriter(csv_file, fieldnames=fieldnames)
    writer.writeheader()

    for i in range(test_start_num, test_end_num):
        if gdl_version == 1:
            fname = output_dir + file_prefex + str(i) + '/finalstate.xml'
        else:
            player_perspective_name = sys.argv[8].upper()
            fname = output_dir + file_prefex + str(i) + '-' + player_perspective_name + '/finalstate.xml'
        if os.path.isfile(fname):
            parse_file_csv(fname, csv_file)