#!/bin/bash

source tests/testConfig/gdl2_kriegTTT_4x4.txt
PARSERESULTS=true

for ((i=0 ; i < NUMTESTS ; i++));
do
    match_id_temp="${MATCH_ID}_${i}"
    if [ "$NUM_ROLES" == 3 ]; then
        java -jar gamecontroller-cli.jar $match_id_temp $GAMEFILE $START_CLOCK $PLAY_CLOCK $GDL_VERSION -$PLAYER_NAME_1 $ROLE_ID_1 -$PLAYER_NAME_2 $ROLE_ID_2 -$PLAYER_NAME_3 $ROLE_ID_3 -printxml $OUTPUT_DIR $STYLESHEET
    else
        java -jar gamecontroller-cli.jar $match_id_temp $GAMEFILE $START_CLOCK $PLAY_CLOCK $GDL_VERSION -$PLAYER_NAME_1 $ROLE_ID_1 -$PLAYER_NAME_2 $ROLE_ID_2 -printxml $OUTPUT_DIR $STYLESHEET
    fi
done

if [ "$PARSERESULTS" == true ]; then
    if [ "$GDL_VERSION" == 1 ]; then
        python tests/testAgent/parseXML.py $OUTPUT_DIR "${MATCH_ID}_" $NUMTESTS $GAMENAME $GDL_VERSION $PLAY_CLOCK
    else
        python tests/testAgent/parseXML.py $OUTPUT_DIR "${MATCH_ID}_" $NUMTESTS $GAMENAME $GDL_VERSION $PLAY_CLOCK $ROLE_NAME_1
    fi
fi

$SHELL