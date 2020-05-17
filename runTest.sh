#!/bin/bash

source tests/testConfig/gdl2_montyhall.txt
PARSERESULTS=false

for ((i=0 ; i < NUMTESTS ; i++));
do
    match_id_temp="${MATCH_ID}_${i}"
    java -jar gamecontroller-cli.jar $match_id_temp $GAMEFILE $START_CLOCK $PLAY_CLOCK $GDL_VERSION -$PLAYER_NAME_1 $ROLE_ID_1 -$PLAYER_NAME_2 $ROLE_ID_2 -printxml $OUTPUT_DIR $STYLESHEET
done

if [ "$PARSERESULTS" == true ]; then
    python tests/testAgent/parseXML.py $OUTPUT_DIR "${MATCH_ID}_" $NUMTESTS $GAMENAME $GDL_VERSION
fi

$SHELL