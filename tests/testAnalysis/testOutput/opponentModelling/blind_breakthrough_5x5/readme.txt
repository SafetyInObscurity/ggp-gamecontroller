This set of data compares the basic ahyperlt using the linear combination to the ophyperb using the square likelihood calc
The ophyperb saves the probability distribution of it's moves using both the original hyperplayer model (numMoves * likelihood)
    and the opponent modelling with full information model (trueDist * likelihood^2)
This also uses a backtracking of 1 and a 1/10 update max time

The 4x4 is using the same from smaller_games and was used for time saving (will update later)