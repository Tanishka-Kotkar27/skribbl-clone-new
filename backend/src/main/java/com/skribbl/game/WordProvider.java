package com.skribbl.game;

import java.util.List;
import java.util.Set;

/** Source of candidate words. Implemented by {@code WordService}. */
public interface WordProvider {

    /**
     * @param settings supplies host custom words
     * @param exclude  lowercased words already used this game
     * @param count    how many distinct words are wanted
     */
    List<String> pick(GameSettings settings, Set<String> exclude, int count);
}
