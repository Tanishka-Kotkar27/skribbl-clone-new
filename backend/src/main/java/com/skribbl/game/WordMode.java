package com.skribbl.game;

/** Optional word modes from the assignment brief. */
public enum WordMode {
    /** Drawer picks one of N words. Guessers see blanks. */
    NORMAL,
    /** Drawer does not see the word either. */
    HIDDEN,
    /** Two words combined, e.g. "flying toaster". */
    COMBINATION
}
